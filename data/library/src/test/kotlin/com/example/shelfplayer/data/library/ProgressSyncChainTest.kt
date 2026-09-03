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
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.playback.ExternalSessionCheck
import com.example.shelfplayer.core.model.playback.ServerProgress
import com.example.shelfplayer.core.model.playback.SessionProgress
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.core.network.fake.FakeAudiobookshelfGateway
import com.example.shelfplayer.core.network.fixture.FixtureLibraryLoader
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.core.network.gateway.PlaybackApi
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.download.SmartDownload
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
 * Four separate defects were found and fixed in this path — R-86, R-87, R-88, R-91 — and after every one
 * of them a device still resumed in the wrong place. Each was proved by a test of *one* repository, and
 * the thing that kept breaking lived between two of them: `DefaultPlaybackRepository.recordPosition`
 * raises `media_progress.hasUnsyncedChanges`, `DefaultSessionSyncRepository` is what lowers it again, and
 * `checkServerPosition` refuses to ask the server while it is up. Three collaborators, one boolean, and
 * no test that held all three at once.
 *
 * So this holds all three: the real playback repository, the real session-sync repository, the real DAOs
 * and a real Room database, with only the network faked. The sequences it replays are the ones
 * `PlaybackService.PlayerEvents` actually emits, including the duplicate callback that produced the
 * contradiction in the device log — a `200` for 22461280ms at 19:58:08, and `stored=22461280ms
 * player=22461280ms` still claiming unsent progress 46 seconds later.
 *
 * ### What it does not reproduce
 *
 * `PlaybackService` itself, which needs a bound `MediaLibraryService` and an emulator. What is asserted
 * here is the invariant that makes the service's ordering safe rather than lucky: **no interleaving of a
 * position write and its own acknowledgement may leave the row claiming unsent progress.** The service
 * now serialises the two, and `a record that arrives after its own acknowledgement leaves the row clean`
 * is the case that says it does not have to.
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
     * **The reported case, end to end: pause here, move it there, press Play.**
     *
     * Everything above is the setup for this. The listener pauses at 6:14:21 and the sync takes it; the
     * web player moves the book to 10:39:02; Play asks the server **once** and is told to go there.
     *
     * `requests` is asserted because the answer arriving is not enough — it has to arrive without the
     * account-wide sweep that made the first version of this check take seconds, and it has to arrive at
     * all, which is what the flag was preventing.
     */
    @Test
    fun `a pause, a move on another device, and a Play that is told to follow`() = runTest {
        val sessionId = openSession()
        pauseAt(PAUSED_AT, sessionId)
        gateway.serverProgress = AppResult.Success(
            ServerProgress(position = MOVED_TO, updatedAt = Instant.ofEpochMilli(2_000L), isFinished = false),
        )

        val outcome = playback.checkServerPosition(BOOK, localPosition = PAUSED_AT)

        assertEquals(ExternalSessionCheck.Ahead(MOVED_TO), outcome)
        assertEquals(1, gateway.progressReads, "one request for one book, and exactly one")
    }

    /**
     * The same run, ending where `ResumeSurface` takes over.
     *
     * The adopted position is what `PlaybackController.resumeLoadedAt` receives, and `ResumeSurfaceTest`
     * pins what it does with it: prepare if needed, `seekTo(10:39:02)`, then `play()`. The two halves are
     * asserted in two modules because `ResumeSurface` is internal to `:playback`; this is the seam between
     * them, and it carries the number.
     */
    @Test
    fun `the position handed to the resume is the one the other device reached`() = runTest {
        val sessionId = openSession()
        pauseAt(PAUSED_AT, sessionId)
        gateway.serverProgress = AppResult.Success(
            ServerProgress(position = MOVED_TO, updatedAt = Instant.ofEpochMilli(2_000L), isFinished = false),
        )

        val outcome = playback.checkServerPosition(BOOK, localPosition = PAUSED_AT)
        val adopted = (outcome as? ExternalSessionCheck.Ahead)?.position

        assertEquals(MOVED_TO, adopted)
        assertEquals(37_142_000L, adopted?.inWholeMilliseconds, "10:39:02, the position the web player reached")
    }

    /**
     * The pause callback, in the order `PlaybackService.onIsPlayingChanged(false)` emits it.
     *
     * Record, then the sync that acknowledges the record. Those were two independent `launch`es until the
     * device log showed the acknowledgement being undone by its own producer.
     */
    private suspend fun pauseAt(position: Duration, sessionId: String) {
        playback.recordPosition(BOOK, position = position, duration = BOOK_DURATION)
        val result = sync.syncOpenSession(
            sessionId = sessionId,
            progress = SessionProgress(position = position, duration = BOOK_DURATION, timeListened = 1.minutes),
            updatedAt = clock.now(),
            trigger = SyncTrigger.Paused,
        )
        assertIs<AppResult.Success<Unit>>(result)
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
