package com.example.shelfplayer.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.database.RoomDatabaseTransactionRunner
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.dao.ProgressDao
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.MediaProgressEntity
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.network.fake.FakeAudiobookshelfGateway
import com.example.shelfplayer.core.network.fixture.FixtureLibraryLoader
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.download.SmartDownload
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC SYNC-002 — a position write and its own acknowledgement, **forced to interleave**.
 *
 * ### The race four fixes narrowed and none of them closed
 *
 * `media_progress.hasUnsyncedChanges` is raised by a position write and lowered by the upload that carries
 * that position to the server. Those run on different coroutines, and the write used to be three steps:
 * read the row, decide whether anything moved, upsert the answer. A write could therefore read a **dirty**
 * row, the upload could clear the flag in the gap, and the write — having already decided — would upsert
 * `1` back over its own acknowledgement. Nothing lowers it again.
 *
 * Every earlier test of this was *sequential*: record, sync, record, assert. That reproduces the ordinary
 * ordering and not the race, which is why four rounds of "fixed" were followed by a device log showing the
 * flag still stuck. This file holds the write open with a latch and lands the acknowledgement inside it —
 * the one interleaving that the ordering fix at the producer cannot rule out, because the callbacks that
 * are not paired with a sync (the five-second journal, `onPlayerError`) are not serialised with anything.
 *
 * The fix is not a narrower window: `ProgressDao.recordPosition` makes the whole decision **inside one
 * SQL statement**, where SQLite evaluates the `SET` expressions against the row as it was before the
 * update while holding the write lock. There is no gap to interleave into.
 *
 * ### What is still at stake, now that the flag no longer gates the freshness check
 *
 * `DefaultLibraryRepository.writeProgress` declines to overwrite a flagged row — correctly, since that
 * would lose a position the server has not been told about. A row that *wrongly* claims unsent progress is
 * therefore a row no account sync can ever correct, which is a stuck position rather than a lost one. The
 * resume no longer reads the flag (`docs/risks.md` R-95), and this invariant still matters.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgressWriteInterleavingTest {

    private lateinit var database: ShelfPlayerDatabase
    private lateinit var latching: LatchingProgressDao
    private lateinit var repository: DefaultPlaybackRepository
    private lateinit var gateway: FakeAudiobookshelfGateway

    private val clock = TestAppClock()
    private val profileId = ProfileId("fixture-profile")

    /**
     * One scheduler for the fixture and every case, and a **standard** dispatcher over it.
     *
     * Standard rather than unconfined because the point of this file is that two coroutines interleave at
     * a chosen point: an unconfined dispatcher runs a `launch` eagerly, which would carry the held write
     * past the latch before the test could land the acknowledgement inside it. One scheduler because
     * `runTest` refuses to mix two, and `setUp` and the cases have to share the dispatcher the repository
     * was built with.
     */
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    @Before
    fun setUp() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        latching = LatchingProgressDao(database.progressDao())
        val logger = RedactingLogger(RecordingLogSink(), DefaultRedactor(RedactionPolicy.Default))
        gateway = FakeAudiobookshelfGateway(
            loader = FixtureLibraryLoader(),
            clock = TestAppClock(),
            logger = logger,
            ioDispatcher = dispatcher,
        )
        seedProfile()
        // The progress row has a foreign key to the book, so the fixture library has to be cached first —
        // the same reason `ProgressSyncChainTest` refreshes before it writes a position.
        DefaultLibraryRepository(
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
        ).refresh(profileId)
        repository = DefaultPlaybackRepository(
            profileRepository = StubProfile(profileId),
            profileDao = database.profileDao(),
            progressDao = latching,
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
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * **The race, forced: a duplicate write held open across the acknowledgement leaves the row clean.**
     *
     * The sequence, and every step of it is the device log's:
     *
     *  1. a position is journalled at 6:14:21, so the row is dirty — this device owes the server a write;
     *  2. a **duplicate** write of the same position begins — a pause from audio-focus loss arriving behind
     *     a user pause, or the five-second journal tick landing on a standing player — and is held after it
     *     has read the dirty row;
     *  3. the upload acknowledges 6:14:21 and the flag is cleared;
     *  4. the held write is released and completes.
     *
     * The row must be **clean** at the end. Under the previous Kotlin-side decision it was dirty: step 2
     * had already concluded "this row claims unsent progress, so it still does" and step 4 wrote that
     * conclusion over step 3's fact.
     */
    @Test
    fun `a duplicate write held across the acknowledgement leaves the row clean`() = runTest(dispatcher) {
        repository.recordPosition(BOOK, position = PAUSED_AT, duration = BOOK_DURATION)
        assertTrue(storedProgress().hasUnsyncedChanges, "a fresh local position is unsent by definition")

        latching.holdNextRead()
        val duplicate = launch { repository.recordPosition(BOOK, position = PAUSED_AT, duration = BOOK_DURATION) }
        latching.awaitHeld()

        val cleared = clearFlagFor(PAUSED_AT)
        assertEquals(1, cleared, "the upload covered the stored position")
        assertFalse(storedProgress().hasUnsyncedChanges, "the acknowledgement landed while the write was held")

        latching.release()
        duplicate.join()

        assertFalse(
            storedProgress().hasUnsyncedChanges,
            "a write that changed nothing re-raised the flag over its own acknowledgement",
        )
    }

    /**
     * A drift of milliseconds is the same position, held open the same way.
     *
     * Every write reads the player afresh rather than remembering, so two records of a *standing* player
     * land a few milliseconds apart — two consecutive syncs have been observed three milliseconds apart.
     * Exact equality would have made this case dirty the row on almost every real pause, which is the
     * failure mode that looks like the acknowledgement simply never working.
     */
    @Test
    fun `a duplicate write that drifted by milliseconds leaves the row clean`() = runTest(dispatcher) {
        repository.recordPosition(BOOK, position = PAUSED_AT, duration = BOOK_DURATION)

        latching.holdNextRead()
        val duplicate = launch {
            repository.recordPosition(BOOK, position = PAUSED_AT + 3.milliseconds, duration = BOOK_DURATION)
        }
        latching.awaitHeld()
        clearFlagFor(PAUSED_AT)
        latching.release()
        duplicate.join()

        assertFalse(storedProgress().hasUnsyncedChanges)
    }

    /**
     * **And the other direction, which must not be lost.** A write that genuinely moved raises the flag
     * even when an acknowledgement lands inside it.
     *
     * This is the half a blanket "never re-raise" would break. The listener kept playing; the position in
     * the row is now past the one the server took, and the server has not been told. If the flag were
     * cleared here the next account sync would overwrite the newer position with the older one it is
     * holding — product priority 2, and a worse outcome than the stuck flag this file is about.
     */
    @Test
    fun `a write that moved on still raises the flag despite an acknowledgement inside it`() = runTest(dispatcher) {
        repository.recordPosition(BOOK, position = PAUSED_AT, duration = BOOK_DURATION)

        latching.holdNextRead()
        val moved = launch {
            repository.recordPosition(BOOK, position = PAUSED_AT + 30.seconds, duration = BOOK_DURATION)
        }
        latching.awaitHeld()
        clearFlagFor(PAUSED_AT)
        assertFalse(storedProgress().hasUnsyncedChanges)
        latching.release()
        moved.join()

        assertTrue(
            storedProgress().hasUnsyncedChanges,
            "the position moved past what the server took, so the server has not seen it",
        )
        assertEquals((PAUSED_AT + 30.seconds).inWholeMilliseconds, storedProgress().positionMillis)
    }

    /**
     * The acknowledgement landing **after** the whole write is the ordinary ordering, and it wins.
     *
     * Included because it is the case the flag exists to serve, and because it says what the interleaving
     * cases do not: the clear is not being made weak to accommodate them. A clear whose position matches
     * the row's clears it, whenever it arrives.
     */
    @Test
    fun `an acknowledgement after the write clears the flag`() = runTest(dispatcher) {
        repository.recordPosition(BOOK, position = PAUSED_AT, duration = BOOK_DURATION)
        repository.recordPosition(BOOK, position = PAUSED_AT, duration = BOOK_DURATION)

        assertEquals(1, clearFlagFor(PAUSED_AT))
        assertFalse(storedProgress().hasUnsyncedChanges)
    }

    /** The exact call `DefaultSessionSyncRepository.markProgressSynced` makes after a successful upload. */
    private suspend fun clearFlagFor(position: Duration): Int = database.progressDao().markProgressSynced(
        profileId = profileId.value,
        bookKey = EntityKey.of(SERVER, BOOK.value),
        positionMillis = position.inWholeMilliseconds,
        toleranceMillis = 1_000L,
    )

    /** Read through the undecorated DAO, so an assertion never trips the latch. */
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
     * A [ProgressDao] that can be stopped at the read a position write performs, and released.
     *
     * The barrier is the point. `DefaultPlaybackRepository.recordPosition` reads the row before it writes —
     * for the finished threshold and the halfway download mark — and that read is where a Kotlin-side
     * decision about the flag would have been made. Holding the write there and completing the
     * acknowledgement inside the hold is the interleaving no sequential test can produce.
     *
     * One-shot, and armed explicitly, so the arrange steps and the assertions run unimpeded through the
     * same object.
     */
    private class LatchingProgressDao(private val delegate: ProgressDao) : ProgressDao by delegate {
        private var gate: CompletableDeferred<Unit>? = null
        private var reached: CompletableDeferred<Unit>? = null
        private var armed = false

        fun holdNextRead() {
            gate = CompletableDeferred()
            reached = CompletableDeferred()
            armed = true
        }

        /** Suspends until a write has read the row and stopped there. */
        suspend fun awaitHeld() {
            reached?.await()
        }

        /** Lets the held write proceed to its upsert. [gate] outlives [armed] so this can still find it. */
        fun release() {
            gate?.complete(Unit)
        }

        override suspend fun findProgress(profileId: String, bookKey: String): MediaProgressEntity? {
            val row = delegate.findProgress(profileId, bookKey)
            // Disarmed before suspending, so the arrange steps and the assertions run through the same
            // object unimpeded and only the one write under test is held.
            if (!armed) return row
            armed = false
            reached?.complete(Unit)
            gate?.await()
            return row
        }
    }

    /** The active profile, without a database of profiles behind it. */
    private class StubProfile(private var active: ProfileId?) : ProfileRepository {
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
        val BOOK = LibraryItemId("book-voyage-1")
        val BOOK_DURATION: Duration = 12.hours

        /** 6:14:21, the position the device log's `200` accepted and the flag then claimed was unsent. */
        val PAUSED_AT: Duration = 22_461_280.milliseconds
    }
}
