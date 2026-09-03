package com.example.shelfplayer.data.library

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.common.time.ServerClock
import com.example.shelfplayer.core.database.RoomDatabaseTransactionRunner
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.MediaProgressEntity
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.datastore.AppSettingsSerializer
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.playback.ExternalSessionCheck
import com.example.shelfplayer.core.model.playback.ServerProgress
import com.example.shelfplayer.core.model.playback.SessionProgress
import com.example.shelfplayer.core.model.playback.SyncOutcome
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.core.network.fake.FakeAudiobookshelfGateway
import com.example.shelfplayer.core.network.fixture.FixtureLibraryLoader
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.core.network.gateway.PlaybackApi
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.download.SmartDownload
import com.example.shelfplayer.domain.playback.ResumeBaseline
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC SYNC-002 — the whole chain from a pause to a resume, against a real database.
 *
 * ### Why this is not another repository test
 *
 * Five separate defects were found and fixed in this path — R-86, R-87, R-88, R-91, R-93 — and after every
 * one of them a device still resumed in the wrong place. Each was proved by a test of *one* repository,
 * and the thing that kept breaking lived between two of them.
 *
 * So this holds all of them at once: the real playback repository, the real session-sync repository, the
 * real [ResumeBaseline], the real DAOs and a real Room database, with only the network faked. The
 * sequences it replays are the ones `PlaybackService.PlayerEvents` actually emits — [pauseAt] is
 * `onCameToRest` plus the promotion `SessionSyncCoordinator.syncNow` performs, in that order.
 *
 * ### The baseline is what the freshness check now decides from
 *
 * Not `hasUnsyncedChanges`, which was the fourth wrong answer: it says whether a queue is empty, and the
 * device log's contradiction is one line of it — a `200` accepting `22461280ms` at 19:58:08, and `Skipped
 * the freshness check … stored=22461280ms player=22461280ms` at 19:58:54. An **acknowledged pause** is a
 * fact about both sides, and the cases below are the four states it can be in when Play is pressed:
 * acknowledged and agreed, acknowledged and overtaken, never acknowledged, and invalidated by a local
 * move.
 *
 * ### What it does not reproduce
 *
 * `PlaybackService` itself, which needs a bound `MediaLibraryService` and an emulator, and the seek that
 * applies an adopted position — which is `internal` to `:playback` and pinned by `AtomicResumeTest`. The
 * seam between the two halves is a number, and both sides assert it.
 *
 * The **flag**'s own invariant is still held, in `ProgressWriteInterleavingTest`, with latches: it is no
 * longer load-bearing for the resume, but a row that wrongly claims unsent progress still blocks an
 * account sync from correcting it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgressSyncChainTest {

    private lateinit var database: ShelfPlayerDatabase
    private lateinit var playback: DefaultPlaybackRepository
    private lateinit var library: DefaultLibraryRepository
    private lateinit var sync: DefaultSessionSyncRepository
    private lateinit var settings: AppSettingsDataSource
    private lateinit var storeScope: CoroutineScope
    private lateinit var storeFile: File
    private lateinit var gateway: ChainGateway

    private val sink = RecordingLogSink()
    private val clock = TestAppClock()

    /** The real thing, driven exactly as `PlaybackService` and `SessionSyncCoordinator` drive it. */
    private val baseline = ResumeBaseline()
    private val profileId = ProfileId("fixture-profile")
    private val profiles = StubProfiles(profileId)

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeFile = File(context.cacheDir, "progress-chain-test.pb").also(File::delete)
        val dispatcher = UnconfinedTestDispatcher()
        val logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default))
        storeScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        settings = AppSettingsDataSource(
            dataStore = DataStoreFactory.create(
                serializer = AppSettingsSerializer(),
                scope = storeScope,
                produceFile = { storeFile },
            ),
            logger = logger,
        )
        gateway = ChainGateway(
            FakeAudiobookshelfGateway(
                loader = FixtureLibraryLoader(),
                clock = TestAppClock(),
                logger = logger,
                ioDispatcher = dispatcher,
            ),
        )
        buildRepositories(logger, dispatcher)
        seedProfile()
        settings.setActiveProfile(profileId)
        library.refresh(profileId)
    }

    /** The three real collaborators the chain runs through, split out to keep `setUp` under the limit. */
    private fun buildRepositories(logger: RedactingLogger, dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        playback = DefaultPlaybackRepository(
            profileRepository = profiles,
            profileDao = database.profileDao(),
            progressDao = database.progressDao(),
            libraryDao = database.libraryDao(),
            gateway = gateway,
            downloads = DownloadSupport(
                sessions = OfflineSessionBuilder(
                    libraryDao = database.libraryDao(),
                    progressDao = database.progressDao(),
                    downloads = NoDownloads,
                ),
                smartDownload = SmartDownload { _, _, _, _ -> },
            ),
            clock = clock,
            logger = logger,
            ioDispatcher = dispatcher,
        )
        library = DefaultLibraryRepository(
            libraryDao = database.libraryDao(),
            profileDao = database.profileDao(),
            progressDao = database.progressDao(),
            syncStateDao = database.syncStateDao(),
            gateway = gateway,
            writer = LibrarySnapshotWriter(
                transaction = RoomDatabaseTransactionRunner(database),
                libraryWriteDao = database.libraryWriteDao(),
                progressDao = database.progressDao(),
                historyDao = database.playbackHistoryDao(),
            ),
            clock = TestAppClock(),
            logger = logger,
            ioDispatcher = dispatcher,
        )
        sync = DefaultSessionSyncRepository(
            settings = settings,
            profileDao = database.profileDao(),
            outbox = database.sessionOutboxDao(),
            progressDao = database.progressDao(),
            gateway = gateway,
            serverClock = ServerClock(logger),
            clock = clock,
            logger = logger,
            ioDispatcher = dispatcher,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * **After a successful pause sync the progress row is clean.**
     *
     * The pause callback as the service emits it, now that the two halves are serialised: record the
     * position, then ask for the sync that acknowledges it. If the row is still flagged at the end of
     * this, every freshness check that follows will short-circuit and the resume will never ask the
     * server — which is precisely what four device runs did.
     */
    @Test
    fun `a pause that syncs leaves the progress row clean`() = runTest {
        val sessionId = openSession()

        pauseAt(PAUSED_AT, sessionId)

        assertFalse(storedProgress().hasUnsyncedChanges, "the server took this position; the row must say so")
    }

    /**
     * **A record that arrives after its own acknowledgement leaves the row clean**, and this is the defect.
     *
     * The device log's contradiction, replayed: the sync accepts 22461280ms, and then a *second*
     * `recordPosition` of the same position lands — a pause from audio focus loss following a user pause,
     * or a track callback firing behind a seek. It used to raise `hasUnsyncedChanges` unconditionally, so
     * it re-flagged a row the server had already taken and nothing was left to lower it again.
     *
     * Serialising the producer removes the ordinary race; this removes the *possibility*, which is what
     * makes the fix hold for the callbacks that are not paired with a sync at all.
     */
    @Test
    fun `a record that arrives after its own acknowledgement leaves the row clean`() = runTest {
        val sessionId = openSession()
        pauseAt(PAUSED_AT, sessionId)

        // The duplicate. Same position, no new information, arriving after the upload that took it.
        playback.recordPosition(BOOK, position = PAUSED_AT, duration = BOOK_DURATION)

        assertFalse(storedProgress().hasUnsyncedChanges, "a write that changed nothing invented unsent progress")
    }

    /** A few milliseconds of drift is the same position: every write reads the player afresh. */
    @Test
    fun `a duplicate record that drifted by milliseconds leaves the row clean`() = runTest {
        val sessionId = openSession()
        pauseAt(PAUSED_AT, sessionId)

        playback.recordPosition(BOOK, position = PAUSED_AT + 3.milliseconds, duration = BOOK_DURATION)

        assertFalse(storedProgress().hasUnsyncedChanges)
    }

    /**
     * And a record that genuinely moved **does** raise the flag, which is the half that must not be lost.
     *
     * Product priority 2: a position the server has not taken has to keep claiming so, or the next account
     * sync overwrites it.
     */
    @Test
    fun `a record that moved on still marks the row unsynced`() = runTest {
        val sessionId = openSession()
        pauseAt(PAUSED_AT, sessionId)

        playback.recordPosition(BOOK, position = PAUSED_AT + 30.seconds, duration = BOOK_DURATION)

        assertTrue(storedProgress().hasUnsyncedChanges, "the server has not seen this position")
    }

    /**
     * **The acceptance case, end to end: an acknowledged pause at 6:14:21, a move to 10:39:02, and Play.**
     *
     * Everything above is the setup for this. The listener pauses at 6:14:21 and the sync takes it, so the
     * pause is *acknowledged*; the web player moves the book to 10:39:02; Play asks the server **once** and
     * is told to go there.
     *
     * `progressReads` is asserted because the answer arriving is not enough. It has to arrive without the
     * account-wide sweep that made the first version of this check take seconds, and it has to arrive **at
     * all** — four device runs contain no `GET /api/me/progress/` request whatsoever, because each version
     * of the gate in front of it short-circuited on something local.
     *
     * What happens to 10:39:02 next is `AtomicResumeTest`: the service seeks its own ExoPlayer there,
     * waits for that player's discontinuity, confirms the landing, and only then plays.
     */
    @Test
    fun `an acknowledged pause, a move on another device, and a Play that is told to follow`() = runTest {
        val sessionId = openSession()
        assertTrue(pauseAt(PAUSED_AT, sessionId), "the pause has to reach the server to be a baseline")
        val acknowledged = assertNotNull(baseline.acknowledged(BOOK), "the server took 6:14:21")
        assertEquals(PAUSED_AT, acknowledged.position)
        gateway.serverProgress = AppResult.Success(
            ServerProgress(position = MOVED_TO, updatedAt = Instant.ofEpochMilli(2_000L), isFinished = false),
        )

        val outcome = playback.checkServerPosition(BOOK, acknowledged)

        assertEquals(ExternalSessionCheck.Ahead(MOVED_TO), outcome)
        assertEquals(1, gateway.progressReads, "one request for one book, and exactly one")
        assertEquals(
            37_142_000L,
            (outcome as ExternalSessionCheck.Ahead).position.inWholeMilliseconds,
            "10:39:02, the position the web player reached",
        )
    }

    /**
     * The same run with the server **still holding 6:14:21**: asked, answered, and nothing moved.
     *
     * This is the ordinary resume for one person on one device, and it is the case the request is spent on.
     * Getting `Current` here rather than `Ahead` is what stops every Play seeking to where it already is.
     */
    @Test
    fun `an acknowledged pause the server still holds resumes locally`() = runTest {
        val sessionId = openSession()
        pauseAt(PAUSED_AT, sessionId)
        gateway.serverProgress = AppResult.Success(
            ServerProgress(position = PAUSED_AT, updatedAt = Instant.ofEpochMilli(9_000L), isFinished = false),
        )

        val outcome = playback.checkServerPosition(BOOK, baseline.acknowledged(BOOK))

        assertEquals(ExternalSessionCheck.Current, outcome)
        assertEquals(1, gateway.progressReads)
    }

    /**
     * **A pause whose sync failed is not a baseline, and Play does not ask.**
     *
     * "Failed pause acknowledgement = preserve local because server freshness is ambiguous." The server
     * might be holding our position or something older; there is no way to tell those apart, and adopting
     * on a guess is how a position gets lost (product priority 2). The row keeps its unsent flag and the
     * outbox will carry it up later — that half is `DefaultSessionSyncRepositoryTest`'s.
     */
    @Test
    fun `a pause the server refused is not a baseline and Play asks nothing`() = runTest {
        val sessionId = openSession()
        gateway.syncResult = AppResult.Failure(AppError.Network(summary = "No connection."))

        assertFalse(pauseAt(PAUSED_AT, sessionId), "the sync failed, so nothing was acknowledged")

        assertNull(baseline.acknowledged(BOOK))
        assertTrue(storedProgress().hasUnsyncedChanges, "the position is still owed to the server")
        assertEquals(
            ExternalSessionCheck.Unavailable,
            playback.checkServerPosition(BOOK, baseline.acknowledged(BOOK)),
        )
        assertEquals(0, gateway.progressReads, "an unacknowledged pause has nothing to compare against")
    }

    /**
     * **A pause on a session the server never opened is not a baseline either**, and this one is subtle.
     *
     * A book started while offline has no server session id, so `syncOpenSession` stores the position in the
     * outbox and returns **success without sending anything** — which is the honest answer for an outbox and
     * was, until [SyncOutcome] existed, indistinguishable from an acknowledgement. Promoting on it would
     * build a baseline for a position Audiobookshelf has never seen; the next Play would then read whatever
     * older position the server does hold, conclude another device had moved the book, and **rewind the
     * listener onto it**. Losing a position to a check written to protect positions.
     */
    @Test
    fun `a pause on a session the server never opened is not a baseline`() = runTest {
        val offline = assertIs<AppResult.Success<String>>(
            sync.openSession(
                bookId = BOOK,
                remoteSessionId = null,
                title = "The Voyage",
                author = "A. Cartographer",
                position = Duration.ZERO,
                duration = BOOK_DURATION,
                startedAt = clock.now(),
            ),
        ).value

        assertFalse(pauseAt(PAUSED_AT, offline), "nothing was sent, so nothing was acknowledged")

        assertNull(baseline.acknowledged(BOOK))
        assertEquals(
            ExternalSessionCheck.Unavailable,
            playback.checkServerPosition(BOOK, baseline.acknowledged(BOOK)),
        )
        assertEquals(0, gateway.progressReads)
    }

    /**
     * **A local seek after the pause invalidates the baseline**, so the next Play asks nothing.
     *
     * The acknowledged position described where this device was at rest. A seek moves it, and the server
     * is then holding a position that differs for a reason that has nothing to do with another device —
     * comparing against it would adopt the server's number over the listener's own choice, which is the
     * app overruling them.
     *
     * `PlaybackService.onPositionDiscontinuity` calls `onLocalMove` for exactly this, and it is also what
     * the adopting seek itself triggers: the pause it belonged to is over either way.
     */
    @Test
    fun `a local seek after the pause invalidates the baseline`() = runTest {
        val sessionId = openSession()
        pauseAt(PAUSED_AT, sessionId)
        assertNotNull(baseline.acknowledged(BOOK))

        baseline.onLocalMove()

        assertNull(baseline.acknowledged(BOOK), "the listener moved the book; the pause no longer describes it")
        assertEquals(
            ExternalSessionCheck.Unavailable,
            playback.checkServerPosition(BOOK, baseline.acknowledged(BOOK)),
        )
        assertEquals(0, gateway.progressReads)
    }

    /**
     * **A late acknowledgement for a superseded pause is refused.**
     *
     * The generation guard, driven the way the coordinator drives it: the generation is read before the
     * send, the listener seeks while the request is in flight, and the answer then arrives. Without the
     * guard that answer would promote a record describing a position the player has left — and, because a
     * paused player syncs the same position every thirty seconds, matching on the position alone is not
     * enough to tell "this confirms my pause" from "this confirms a pause two seeks ago".
     */
    @Test
    fun `an acknowledgement that arrives after a seek is refused`() = runTest {
        val generation = baseline.onPaused(BOOK, PAUSED_AT)

        baseline.onLocalMove()

        assertFalse(baseline.onPositionAccepted(BOOK, PAUSED_AT, generation))
        assertNull(baseline.acknowledged(BOOK))
    }

    /**
     * The pause, in the order the service emits it — `PlaybackService.onCameToRest`, whole.
     *
     * Capture the position as pending, journal it, await the sync, and let the sync's success promote the
     * baseline. Each of the four is a step some earlier version skipped or reordered: the awaiting is why
     * `SessionSyncCoordinator.sync` exists at all, and the generation is read *before* the send so a late
     * answer cannot confirm a pause the listener has already moved past.
     *
     * @return whether the server took the position, which is what the service's coroutine sees.
     */
    private suspend fun pauseAt(position: Duration, sessionId: String): Boolean {
        val generation = baseline.onPaused(BOOK, position)
        playback.recordPosition(BOOK, position = position, duration = BOOK_DURATION)
        val result = sync.syncOpenSession(
            sessionId = sessionId,
            progress = SessionProgress(position = position, duration = BOOK_DURATION, timeListened = 1.minutes),
            updatedAt = clock.now(),
            trigger = SyncTrigger.Paused,
        )
        // Only [SyncOutcome.Accepted] promotes. A `Success` carrying `Queued` means the position is safe on
        // this device and the server has never seen it — see the offline case below.
        val accepted = result is AppResult.Success && result.value == SyncOutcome.Accepted
        if (accepted) baseline.onPositionAccepted(BOOK, position, generation)
        return accepted
    }

    private suspend fun openSession(): String = assertIs<AppResult.Success<String>>(
        sync.openSession(
            bookId = BOOK,
            remoteSessionId = "remote-1",
            title = "The Voyage",
            author = "A. Cartographer",
            position = Duration.ZERO,
            duration = BOOK_DURATION,
            startedAt = clock.now(),
        ),
    ).value

    private suspend fun storedProgress(): MediaProgressEntity =
        requireNotNull(database.progressDao().findProgress(profileId.value, EntityKey.of(SERVER, BOOK.value))) {
            "no progress row was written"
        }

    private suspend fun seedProfile() {
        database.profileDao().upsertServer(
            ServerEntity(
                serverId = SERVER,
                displayName = "Demo",
                baseUrl = "https://fixture.invalid",
                detectedVersion = "fixture-0",
                isFixture = true,
                lastFetchedAt = 0,
                authMethodsJson = "[]",
                capabilitiesJson = "[]",
                capabilitiesDetectedAt = null,
            ),
        )
        database.profileDao().upsertProfile(
            ProfileEntity(
                profileId = profileId.value,
                serverId = SERVER,
                remoteUserId = null,
                username = "demo",
                displayName = "Demo listener",
                role = "Listener",
                requiresReauthentication = false,
                lastUsedAt = null,
                isFixture = true,
                accessibleLibrariesJson = "[]",
                hasAllLibraryAccess = true,
                hasAllTagAccess = true,
                canDownload = false,
            ),
        )
    }

    /**
     * The fixture gateway with the two calls this chain turns on scripted and counted.
     *
     * Delegation rather than a hand-written stub: the library refresh that seeds the book needs a working
     * gateway, and only `serverProgress` has an answer worth choosing.
     */
    private class ChainGateway(private val delegate: AudiobookshelfGateway) :
        AudiobookshelfGateway by delegate,
        PlaybackApi by delegate.playback {
        var serverProgress: AppResult<ServerProgress>? = null

        /** The demo document has no server, so the sync has to be answered here or nothing uploads. */
        var syncResult: AppResult<Unit> = AppResult.Success(Unit)

        var progressReads: Int = 0
            private set

        var syncs: Int = 0
            private set

        override val playback: PlaybackApi get() = this

        override suspend fun serverProgress(profileId: ProfileId, bookId: LibraryItemId): AppResult<ServerProgress> {
            progressReads += 1
            return serverProgress ?: delegate.playback.serverProgress(profileId, bookId)
        }

        override suspend fun syncSession(
            profileId: ProfileId,
            sessionId: String,
            progress: SessionProgress,
        ): AppResult<Unit> {
            syncs += 1
            return syncResult
        }
    }

    /** The active profile, without a database of profiles behind it. */
    private class StubProfiles(private var active: ProfileId?) : ProfileRepository {
        override fun observeProfiles(): Flow<List<Profile>> = flowOf(emptyList())

        override fun observeServers(): Flow<List<Server>> = flowOf(emptyList())

        override fun observeActiveProfile(): Flow<Profile?> = flowOf(null)

        override suspend fun activeProfileId(): ProfileId? = active

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> {
            active = profileId
            return AppResult.Success(Unit)
        }
    }

    private companion object {
        const val SERVER = "fixture-server"

        /** A book the fixture library holds, so the progress row has something to hang off. */
        val BOOK = LibraryItemId("book-voyage-1")
        val BOOK_DURATION: Duration = 12.hours

        /** 6:14:21, where the device log's listener paused. */
        val PAUSED_AT: Duration = 22_461_280.milliseconds

        /** 10:39:02, where the web player took the book. */
        val MOVED_TO: Duration = 37_142_000.milliseconds
    }
}
