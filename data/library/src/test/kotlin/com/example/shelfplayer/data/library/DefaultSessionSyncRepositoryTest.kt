package com.example.shelfplayer.data.library

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.common.time.ServerClock
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.entity.BookEntity
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.LibraryEntity
import com.example.shelfplayer.core.database.entity.MediaProgressEntity
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.database.entity.SessionOutboxState
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.datastore.AppSettingsSerializer
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.ServerProbe
import com.example.shelfplayer.core.model.auth.AccountState
import com.example.shelfplayer.core.model.auth.AuthSession
import com.example.shelfplayer.core.model.auth.AuthToken
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.model.library.Bookmark
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibrarySnapshot
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.ListeningSession
import com.example.shelfplayer.core.model.playback.OfflineSession
import com.example.shelfplayer.core.model.playback.OfflineSessionResult
import com.example.shelfplayer.core.model.playback.ServerProgress
import com.example.shelfplayer.core.model.playback.SessionProgress
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.core.network.gateway.AuthApi
import com.example.shelfplayer.core.network.gateway.BookmarkApi
import com.example.shelfplayer.core.network.gateway.CachedLibrary
import com.example.shelfplayer.core.network.gateway.CapabilityResolver
import com.example.shelfplayer.core.network.gateway.DownloadApi
import com.example.shelfplayer.core.network.gateway.FileTransfer
import com.example.shelfplayer.core.network.gateway.LibraryApi
import com.example.shelfplayer.core.network.gateway.ManagementApi
import com.example.shelfplayer.core.network.gateway.PlaybackApi
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.OutputStream
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-004 / PLAY-005 — the outbox, against a real database and a gateway the test controls.
 *
 * The properties asserted here are the ones product priority 2 rests on and none of them is visible by
 * reading the class: a failed sync leaves a durable row, an offline session is uploadable at all, a retry
 * carries the same id, and a server that declines a position is not treated as a server that refused one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultSessionSyncRepositoryTest {

    private lateinit var database: ShelfPlayerDatabase
    private lateinit var repository: DefaultSessionSyncRepository
    private lateinit var settings: AppSettingsDataSource
    private lateinit var storeScope: CoroutineScope
    private lateinit var storeFile: File
    private val sink = RecordingLogSink()
    private val clock = TestAppClock()
    private val gateway = RecordingPlaybackGateway()
    private val profileId = ProfileId("profile-1")

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeFile = File(context.cacheDir, "session-sync-test.pb").also(File::delete)
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
        repository = DefaultSessionSyncRepository(
            settings = settings,
            profileDao = database.profileDao(),
            outbox = database.sessionOutboxDao(),
            progressDao = database.progressDao(),
            gateway = gateway,
            serverClock = ServerClock(logger),
            clock = clock,
            logger = logger,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        seedProfile()
        settings.setActiveProfile(profileId)
    }

    @After
    fun tearDown() {
        database.close()
        storeScope.cancel()
        storeFile.delete()
    }

    /** PRODUCT_SPEC PLAY-005 — "every offline listening session has a UUIDv4 identifier". */
    @Test
    fun `an opened session gets a version 4 uuid of our own`() = runTest {
        val sessionId = assertIs<AppResult.Success<String>>(open()).value

        // Version nibble 4, variant nibble 8-b: the shape PLAY-005 names, checked rather than assumed.
        assertTrue(
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(sessionId),
            sessionId,
        )
    }

    /**
     * The row is written before anything is sent, so a sync that fails leaves the position durably queued.
     *
     * This is the single property the whole design exists for: a position that was attempted and lost is
     * indistinguishable afterwards from one that was never recorded.
     */
    @Test
    fun `a failed sync leaves the position queued`() = runTest {
        val sessionId = openId()
        gateway.syncResult = AppResult.Failure(AppError.Network())

        val result = repository.syncOpenSession(
            sessionId = sessionId,
            progress = SessionProgress(position = 10.minutes, duration = 6.hours, timeListened = 90.seconds),
            updatedAt = clock.now(),
            trigger = SyncTrigger.Interval,
        )

        assertIs<AppResult.Failure>(result)
        val stored = assertNotNull(database.sessionOutboxDao().find(sessionId))
        assertEquals(10.minutes.inWholeMilliseconds, stored.positionMillis)
        assertEquals(1, stored.attempts)
        assertEquals("network", stored.lastErrorCode)
        assertNull(stored.syncedAt)
    }

    /**
     * PRODUCT_SPEC PLAY-004 — the position is sent as observed, and `updatedAt` is when it was observed.
     *
     * No clamping and no restamping. The server takes the newer `updatedAt` and lets progress move backwards,
     * which is the requirement's "never blindly chooses the maximum position" — so an intentional rewind has
     * to arrive as a rewind, under the time it was made.
     */
    @Test
    fun `an intentional rewind is sent as a rewind`() = runTest {
        val sessionId = openId()
        repository.syncOpenSession(
            sessionId,
            SessionProgress(position = 2.hours, duration = 6.hours, timeListened = 60.seconds),
            clock.now(),
            SyncTrigger.Interval,
        )
        clock.advanceBy(1.minutes)
        val rewoundAt = clock.now()

        repository.syncOpenSession(
            sessionId,
            SessionProgress(position = 1.hours, duration = 6.hours, timeListened = 0.seconds),
            rewoundAt,
            SyncTrigger.SeekCompleted,
        )

        assertEquals(1.hours, gateway.syncedProgress.last().position)
        assertEquals(rewoundAt.toEpochMilli(), assertNotNull(database.sessionOutboxDao().find(sessionId)).updatedAt)
    }

    /**
     * A session that never reached the server reports success and stays queued.
     *
     * Honest, and the distinction the two id columns exist for: there is no route that can sync against an id
     * the server never issued, and the position *is* durably recorded. The drain is what uploads it.
     */
    @Test
    fun `a session opened offline is queued rather than synced`() = runTest {
        val sessionId = openId(remoteSessionId = null)

        val result = repository.syncOpenSession(
            sessionId,
            SessionProgress(position = 5.minutes, duration = 6.hours, timeListened = 30.seconds),
            clock.now(),
            SyncTrigger.Interval,
        )

        assertIs<AppResult.Success<Unit>>(result)
        assertEquals(emptyList(), gateway.syncedProgress)
        assertEquals(
            SessionOutboxState.OPEN,
            assertNotNull(database.sessionOutboxDao().find(sessionId)).state,
        )
    }

    /** A drain uploads under the id this device generated, which is what makes a retry idempotent. */
    @Test
    fun `the drain uploads offline sessions under our own id`() = runTest {
        val sessionId = openId(remoteSessionId = null)
        repository.closeSession(
            sessionId,
            SessionProgress(position = 5.minutes, duration = 6.hours, timeListened = 5.minutes),
            clock.now(),
            SyncTrigger.ServiceShutdown,
        )
        gateway.offlineResults = { sessions -> sessions.map { OfflineSessionResult(it.id, true, true) } }

        val drained = repository.drainOutbox()

        assertEquals(1, assertIs<AppResult.Success<Int>>(drained).value)
        assertEquals(listOf(sessionId), gateway.uploaded.single().map { it.id })
        assertEquals(SessionOutboxState.SYNCED, assertNotNull(database.sessionOutboxDao().find(sessionId)).state)
    }

    /**
     * PRODUCT_SPEC PLAY-005 — "retrying a session sync is idempotent".
     *
     * The second attempt carries the same id, which is what the server recognizes as the same session rather
     * than a duplicate. A repository that generated an id per attempt would upload the same listening twice.
     */
    @Test
    fun `a retried drain sends the same session id`() = runTest {
        val sessionId = openId(remoteSessionId = null)
        repository.closeSession(
            sessionId,
            SessionProgress(position = 5.minutes, duration = 6.hours, timeListened = 5.minutes),
            clock.now(),
            SyncTrigger.ServiceShutdown,
        )
        gateway.offlineUpload = AppResult.Failure(AppError.Network())

        repository.drainOutbox()
        gateway.offlineUpload = null
        gateway.offlineResults = { sessions -> sessions.map { OfflineSessionResult(it.id, true, true) } }
        repository.drainOutbox()

        assertEquals(
            listOf(listOf(sessionId), listOf(sessionId)),
            gateway.uploaded.map { batch ->
                batch.map { it.id }
            },
        )
    }

    /**
     * PRODUCT_SPEC PLAY-004 — accepted with the progress declined is the conflict rule working.
     *
     * The row leaves the queue and the fact is recorded. Retrying it would be the app arguing with the server
     * about a position the server already knows is older than the one it holds.
     */
    @Test
    fun `a declined position is recorded rather than retried`() = runTest {
        val sessionId = openId(remoteSessionId = null)
        repository.closeSession(
            sessionId,
            SessionProgress(position = 5.minutes, duration = 6.hours, timeListened = 5.minutes),
            clock.now(),
            SyncTrigger.ServiceShutdown,
        )
        gateway.offlineResults = { sessions ->
            sessions.map { OfflineSessionResult(it.id, wasAccepted = true, wasProgressApplied = false) }
        }

        repository.drainOutbox()
        repository.drainOutbox()

        assertEquals(1, gateway.uploaded.size)
        val stored = assertNotNull(database.sessionOutboxDao().find(sessionId))
        assertEquals(SessionOutboxState.SYNCED, stored.state)
        assertEquals(false, stored.wasProgressApplied)
        assertEquals(1, repository.observeDiagnostics().first().progressDeclined)
    }

    /** A session the batch did not mention keeps its row: the case that would otherwise vanish silently. */
    @Test
    fun `a session the server did not answer for stays queued`() = runTest {
        val sessionId = openId(remoteSessionId = null)
        repository.closeSession(
            sessionId,
            SessionProgress(position = 5.minutes, duration = 6.hours, timeListened = 5.minutes),
            clock.now(),
            SyncTrigger.ServiceShutdown,
        )
        gateway.offlineResults = { emptyList() }

        assertEquals(0, assertIs<AppResult.Success<Int>>(repository.drainOutbox()).value)

        val stored = assertNotNull(database.sessionOutboxDao().find(sessionId))
        assertEquals(SessionOutboxState.PENDING, stored.state)
        assertEquals("not_accepted", stored.lastErrorCode)
    }

    /**
     * A session left `Open` by a process death is finalized by the drain and uploaded.
     *
     * Without this, the one session nobody is coming back to would be the one the outbox never sent.
     */
    @Test
    fun `the drain finalizes a session left open by a previous process`() = runTest {
        val sessionId = openId(remoteSessionId = null)
        gateway.offlineResults = { sessions -> sessions.map { OfflineSessionResult(it.id, true, true) } }

        repository.drainOutbox()

        assertEquals(listOf(sessionId), gateway.uploaded.single().map { it.id })
    }

    /**
     * PRODUCT_SPEC PLAY-005 — "retained for seven days, then compacted".
     *
     * The queued row in this test is older than the retention. It must survive, because it is the one row whose
     * listening has never reached the server.
     */
    @Test
    fun `compaction removes uploaded sessions and keeps queued ones`() = runTest {
        val queued = openId(remoteSessionId = null)
        val uploaded = openId(remoteSessionId = null)
        repository.closeSession(
            uploaded,
            SessionProgress(position = 5.minutes, duration = 6.hours, timeListened = 5.minutes),
            clock.now(),
            SyncTrigger.ServiceShutdown,
        )
        gateway.offlineResults = { sessions ->
            sessions.filter { it.id == uploaded }.map { OfflineSessionResult(it.id, true, true) }
        }
        repository.drainOutbox()
        gateway.offlineResults = { emptyList() }

        clock.advanceBy(8.days)
        repository.drainOutbox()

        assertNull(database.sessionOutboxDao().find(uploaded))
        assertNotNull(database.sessionOutboxDao().find(queued))
    }

    /** PRODUCT_SPEC SET-002 — the counts the About screen reads. */
    @Test
    fun `diagnostics count what is queued and what the server took`() = runTest {
        val sessionId = openId()
        repository.syncOpenSession(
            sessionId,
            SessionProgress(position = 10.minutes, duration = 6.hours, timeListened = 60.seconds),
            clock.now(),
            SyncTrigger.Paused,
        )

        val diagnostics = repository.observeDiagnostics().first()

        assertEquals(1, diagnostics.sessionsRecorded)
        assertEquals(1, diagnostics.sessionsSynced)
        assertEquals(0, diagnostics.sessionsPending)
        assertEquals(SyncTrigger.Paused, diagnostics.lastTrigger)
        assertEquals(clock.now(), diagnostics.lastSyncedAt)
    }

    /** An open session counts as pending: the server has not accepted it, which is all "pending" means. */
    @Test
    fun `an open session is reported as pending`() = runTest {
        openId()

        val diagnostics = repository.observeDiagnostics().first()

        assertEquals(1, diagnostics.sessionsPending)
        assertEquals(1, diagnostics.sessionsOpen)
        assertEquals(0, diagnostics.sessionsSynced)
    }

    /** PRODUCT_SPEC 14.5 — the log says how much was sent, never what was listened to. */
    @Test
    fun `no book title reaches the log`() = runTest {
        val sessionId = openId(remoteSessionId = null)
        repository.closeSession(
            sessionId,
            SessionProgress(position = 5.minutes, duration = 6.hours, timeListened = 5.minutes),
            clock.now(),
            SyncTrigger.ServiceShutdown,
        )
        gateway.offlineResults = { sessions -> sessions.map { OfflineSessionResult(it.id, true, true) } }
        repository.drainOutbox()

        assertTrue(sink.text.isNotEmpty())
        assertTrue(!sink.text.contains(BOOK_TITLE), sink.text)
        assertTrue(!sink.text.contains(BOOK_AUTHOR), sink.text)
    }

    /** A sync against a session this device does not have is a conflict, not a silent no-op. */
    @Test
    fun `syncing an unknown session fails`() = runTest {
        val result = repository.syncOpenSession(
            sessionId = "never-recorded",
            progress = SessionProgress(position = 1.minutes, duration = 6.hours, timeListened = 0.seconds),
            updatedAt = clock.now(),
            trigger = SyncTrigger.Interval,
        )

        assertIs<AppError.Conflict>(assertIs<AppResult.Failure>(result).error)
    }

    // ------------------------------------------- PRODUCT_SPEC SYNC-002, not overwriting another device

    /*
     * `POST /api/session/{id}/sync` is a write, and Audiobookshelf carries the session's `currentTime`
     * through to the item's media progress. BookWave sent one every thirty seconds whether or not the
     * position had moved — so a listener who paused, went to the web player and moved the book had their
     * change overwritten before they came back. A device log caught three of those writes in under a
     * minute, all carrying the same 22207165ms while the player sat paused.
     */

    /** **A position the server has already accepted is not sent again.** This is the fix. */
    @Test
    fun `a sync with nothing new does not reach the server`() = runTest {
        val sessionId = openId()
        val paused = SessionProgress(position = 40.minutes, duration = 6.hours, timeListened = 2.minutes)
        assertIs<AppResult.Success<Unit>>(
            repository.syncOpenSession(sessionId, paused, clock.now(), SyncTrigger.Paused),
        )
        val afterFirst = gateway.syncCount

        // The interval tick thirty seconds later, with the player still paused on the same position.
        val result = repository.syncOpenSession(sessionId, paused, clock.now(), SyncTrigger.Interval)

        assertIs<AppResult.Success<Unit>>(result)
        assertEquals(afterFirst, gateway.syncCount, "the server's position was overwritten with our own")
    }

    /**
     * **A paused player's few milliseconds of drift still count as nothing new**, and the log is why.
     *
     * Two consecutive syncs of the same paused player reported `22256204ms` and `22256207ms`, because the
     * position is read back from the player on every sync rather than remembered. Exact equality would
     * have let the second one through and overwritten the other device after all.
     */
    @Test
    fun `a paused position that drifted a few milliseconds is still nothing new`() = runTest {
        val sessionId = openId()
        repository.syncOpenSession(
            sessionId,
            SessionProgress(position = 22_256_204.milliseconds, duration = 6.hours, timeListened = 2.minutes),
            clock.now(),
            SyncTrigger.Paused,
        )
        val afterFirst = gateway.syncCount

        repository.syncOpenSession(
            sessionId,
            SessionProgress(position = 22_256_207.milliseconds, duration = 6.hours, timeListened = 2.minutes),
            clock.now(),
            SyncTrigger.Interval,
        )

        assertEquals(afterFirst, gateway.syncCount, "three milliseconds of drift is not news")
    }

    /**
     * A pause, a seek or a chapter change is **somebody's decision**, and is reported even when the
     * position barely moved.
     *
     * Only the two unprompted triggers are skippable. A listener who nudged the bar and let go chose that
     * position, and the server hearing about it is the point of the sync.
     */
    @Test
    fun `a prompted trigger is sent even when nothing moved`() = runTest {
        val sessionId = openId()
        val where = SessionProgress(position = 40.minutes, duration = 6.hours, timeListened = 2.minutes)
        repository.syncOpenSession(sessionId, where, clock.now(), SyncTrigger.Interval)
        val afterFirst = gateway.syncCount

        repository.syncOpenSession(sessionId, where, clock.now(), SyncTrigger.SeekCompleted)

        assertEquals(afterFirst + 1, gateway.syncCount)
    }

    /** A position that moved is sent, which is every sync while a book is actually playing. */
    @Test
    fun `a sync whose position moved reaches the server`() = runTest {
        val sessionId = openId()
        repository.syncOpenSession(
            sessionId,
            SessionProgress(position = 40.minutes, duration = 6.hours, timeListened = 2.minutes),
            clock.now(),
            SyncTrigger.Interval,
        )
        val afterFirst = gateway.syncCount

        repository.syncOpenSession(
            sessionId,
            SessionProgress(position = 41.minutes, duration = 6.hours, timeListened = 3.minutes),
            clock.now(),
            SyncTrigger.Interval,
        )

        assertEquals(afterFirst + 1, gateway.syncCount)
    }

    /**
     * Listening time that accrued without the position moving is still reported.
     *
     * The two are separate facts and the server stores both. A book played at a stall — buffering, a
     * sleep-timer fade — can add time without adding position.
     */
    @Test
    fun `a sync reporting more listening time reaches the server`() = runTest {
        val sessionId = openId()
        repository.syncOpenSession(
            sessionId,
            SessionProgress(position = 40.minutes, duration = 6.hours, timeListened = 2.minutes),
            clock.now(),
            SyncTrigger.Interval,
        )
        val afterFirst = gateway.syncCount

        repository.syncOpenSession(
            sessionId,
            SessionProgress(position = 40.minutes, duration = 6.hours, timeListened = 4.minutes),
            clock.now(),
            SyncTrigger.Interval,
        )

        assertEquals(afterFirst + 1, gateway.syncCount)
    }

    /**
     * **A retry after a failed upload is never skipped**, which is what makes the rule safe.
     *
     * The skip is gated on the last upload having been *accepted*. A row left `Open` by a failure carries
     * a position the server does not have, and dropping its retry would lose progress — product
     * priority 2, and the opposite of the defect this rule fixes.
     */
    @Test
    fun `a retry after a failed sync still reaches the server`() = runTest {
        val sessionId = openId()
        val paused = SessionProgress(position = 40.minutes, duration = 6.hours, timeListened = 2.minutes)
        gateway.syncResult = AppResult.Failure(AppError.Network(summary = "No connection."))
        repository.syncOpenSession(sessionId, paused, clock.now(), SyncTrigger.Paused)
        val afterFailure = gateway.syncCount
        gateway.syncResult = AppResult.Success(Unit)

        repository.syncOpenSession(sessionId, paused, clock.now(), SyncTrigger.Interval)

        assertEquals(afterFailure + 1, gateway.syncCount, "an unsent position must always get another go")
    }

    /** And a **close** is never skipped: it finalizes the session, which the position alone does not. */
    @Test
    fun `closing a session is not skipped when the position has not moved`() = runTest {
        val sessionId = openId()
        val paused = SessionProgress(position = 40.minutes, duration = 6.hours, timeListened = 2.minutes)
        repository.syncOpenSession(sessionId, paused, clock.now(), SyncTrigger.Paused)
        val afterSync = gateway.closeCount

        repository.closeSession(sessionId, paused, clock.now(), SyncTrigger.ServiceShutdown)

        assertEquals(afterSync + 1, gateway.closeCount)
    }

    // ------------------------------------------- PRODUCT_SPEC PLAY-004 / SYNC-002, the flag on the position

    /*
     * `media_progress.hasUnsyncedChanges` is the app's claim that a position has not reached the server.
     * Every `recordPosition` sets it and, until R-86, nothing cleared it: `writeProgress` refuses to
     * overwrite a flagged row, so only a server-sourced write could clear it and a flagged row was exactly
     * what a server-sourced write skipped. It went unnoticed until the freshness check before a Play began
     * reading the flag and stopped asking the server at all.
     */

    /** **A position the server accepted is no longer unsynced**, which is the whole of the fix. */
    @Test
    fun `a synced session clears the book's unsynced flag`() = runTest {
        seedUnsyncedProgress(recordedAt = 1_000L)
        val sessionId = openId()

        val result = repository.syncOpenSession(
            sessionId = sessionId,
            progress = SessionProgress(position = 40.minutes, duration = 6.hours, timeListened = 1.minutes),
            updatedAt = Instant.ofEpochMilli(2_000L),
            trigger = SyncTrigger.Interval,
        )

        assertIs<AppResult.Success<Unit>>(result)
        assertEquals(false, storedProgress().hasUnsyncedChanges)
    }

    /**
     * **A position recorded while the upload was in flight keeps its flag.**
     *
     * The request carried the position as it was when it was built. If the listener played on, the stored
     * row holds more than the server does and is genuinely still unsent — clearing it would let the next
     * account sync overwrite it, which is losing progress.
     *
     * The row has moved to 45 minutes while the request carried 40, and it is that difference the clear
     * reads. It used to read a pair of timestamps instead, and lost a race to them: `recordPosition` and
     * the session sync each stamp their own `clock.now()` on independent coroutines, so the sync's stamp
     * could precede the row's and the clear would match nothing at all — leaving the flag standing
     * forever and the freshness check short-circuiting on it. `docs/risks.md` R-92.
     */
    @Test
    fun `a position recorded after the upload keeps its flag`() = runTest {
        seedUnsyncedProgress(recordedAt = 5_000L, position = 45.minutes)
        val sessionId = openId()

        repository.syncOpenSession(
            sessionId = sessionId,
            progress = SessionProgress(position = 40.minutes, duration = 6.hours, timeListened = 1.minutes),
            updatedAt = Instant.ofEpochMilli(2_000L),
            trigger = SyncTrigger.Interval,
        )

        assertEquals(true, storedProgress().hasUnsyncedChanges, "the row moved on after the request was built")
    }

    /**
     * **And the clear no longer depends on which coroutine stamped its clock first.**
     *
     * This is the case the timestamp version got wrong, and it is why three device logs contain no
     * `GET /api/me/progress/{id}` request at all. The row was written *after* the sync took its stamp —
     * `5_000` against `2_000` — while holding the very position the sync is uploading. The old rule
     * required `updatedAt <= uploadedAt` and so cleared nothing; the position says plainly that the
     * server now holds this, and the flag comes down.
     */
    @Test
    fun `a flag is cleared even when the row was stamped after the upload`() = runTest {
        seedUnsyncedProgress(recordedAt = 5_000L, position = 40.minutes)
        val sessionId = openId()

        repository.syncOpenSession(
            sessionId = sessionId,
            progress = SessionProgress(position = 40.minutes, duration = 6.hours, timeListened = 1.minutes),
            updatedAt = Instant.ofEpochMilli(2_000L),
            trigger = SyncTrigger.Interval,
        )

        assertEquals(false, storedProgress().hasUnsyncedChanges, "the server holds this position")
    }

    /**
     * **A skipped sync clears the flag too**, and forgetting that is what broke the device test.
     *
     * The skip's whole premise is that the server already holds this position — so the progress row has
     * nothing unsent, and must not go on claiming it does. The first version of the redundant-sync guard
     * returned early without saying so, which left a flag set by the pause standing through every
     * subsequent skip; `checkServerPosition` then answered `Current` without a request, and the log shows
     * exactly that: eight `Nothing new to sync` lines and no `GET /api/me/progress` anywhere. R-92.
     */
    @Test
    fun `a skipped sync still clears the unsynced flag`() = runTest {
        val sessionId = openId()
        val paused = SessionProgress(position = 40.minutes, duration = 6.hours, timeListened = 2.minutes)
        repository.syncOpenSession(sessionId, paused, clock.now(), SyncTrigger.Paused)
        // What a pause from audio focus loss does: records the position again and re-raises the flag.
        seedUnsyncedProgress(recordedAt = 9_000L, position = 40.minutes)
        assertEquals(true, storedProgress().hasUnsyncedChanges)
        val beforeSkip = gateway.syncCount

        repository.syncOpenSession(sessionId, paused, clock.now(), SyncTrigger.Interval)

        assertEquals(beforeSkip, gateway.syncCount, "this sync had nothing to say")
        assertEquals(false, storedProgress().hasUnsyncedChanges, "but the server does hold this position")
    }

    /** A failed upload leaves the claim standing: the position is still this device's alone. */
    @Test
    fun `a failed sync leaves the unsynced flag alone`() = runTest {
        seedUnsyncedProgress(recordedAt = 1_000L)
        val sessionId = openId()
        gateway.syncResult = AppResult.Failure(AppError.Network(summary = "No connection."))

        repository.syncOpenSession(
            sessionId = sessionId,
            progress = SessionProgress(position = 40.minutes, duration = 6.hours, timeListened = 1.minutes),
            updatedAt = Instant.ofEpochMilli(2_000L),
            trigger = SyncTrigger.Interval,
        )

        assertEquals(true, storedProgress().hasUnsyncedChanges)
    }

    /** The row `recordPosition` would have written: a local position nothing has uploaded yet. */
    private suspend fun seedUnsyncedProgress(recordedAt: Long, position: Duration = 40.minutes) {
        seedBook()
        database.progressDao().upsertProgress(
            listOf(
                MediaProgressEntity(
                    progressKey = EntityKey.scoped(profileId.value, BOOK_KEY),
                    profileId = profileId.value,
                    bookKey = BOOK_KEY,
                    serverId = SERVER,
                    positionMillis = position.inWholeMilliseconds,
                    durationMillis = 6.hours.inWholeMilliseconds,
                    isFinished = false,
                    updatedAt = recordedAt,
                    hasUnsyncedChanges = true,
                ),
            ),
        )
    }

    /**
     * The library and book the progress row hangs off.
     *
     * `media_progress` is keyed through both, so a position for a book this device has never heard of is
     * not a state the app can reach — and not one a test should be able to fabricate either.
     */
    private suspend fun seedBook() {
        database.libraryWriteDao().upsertLibraries(
            listOf(
                LibraryEntity(
                    libraryKey = LIBRARY_KEY,
                    serverId = SERVER,
                    remoteId = "lib-1",
                    name = "Fiction",
                    kind = "Book",
                    displayOrder = 0,
                    remoteUpdatedAt = null,
                    lastFetchedAt = 0,
                    isDeleted = false,
                    finishedTimeRemainingSeconds = null,
                ),
            ),
        )
        database.libraryWriteDao().upsertBooks(
            listOf(
                BookEntity(
                    bookKey = BOOK_KEY,
                    serverId = SERVER,
                    remoteId = "book-1",
                    libraryKey = LIBRARY_KEY,
                    title = BOOK_TITLE,
                    subtitle = null,
                    narratorsJson = "[]",
                    genresJson = "[]",
                    tagsJson = "[]",
                    durationMillis = 6.hours.inWholeMilliseconds,
                    description = null,
                    publishedYear = null,
                    publisher = null,
                    language = null,
                    isbn = null,
                    asin = null,
                    isExplicit = false,
                    isAbridged = false,
                    coverPath = null,
                    trackCount = 1,
                    sizeBytes = 0,
                    remoteUpdatedAt = null,
                    addedAt = null,
                    lastFetchedAt = 0,
                    isDeleted = false,
                    localAvailability = "NotDownloaded",
                ),
            ),
        )
    }

    private suspend fun storedProgress(): MediaProgressEntity =
        requireNotNull(database.progressDao().findProgress(profileId.value, BOOK_KEY)) {
            "no progress row was written"
        }

    private suspend fun open(remoteSessionId: String? = "remote-1") = repository.openSession(
        bookId = LibraryItemId("book-1"),
        remoteSessionId = remoteSessionId,
        title = BOOK_TITLE,
        author = BOOK_AUTHOR,
        position = 0.seconds,
        duration = 6.hours,
        startedAt = clock.now(),
    )

    private suspend fun openId(remoteSessionId: String? = "remote-1") =
        assertIs<AppResult.Success<String>>(open(remoteSessionId)).value

    private suspend fun seedProfile() {
        database.profileDao().upsertServer(
            ServerEntity(
                serverId = SERVER,
                displayName = "Demo",
                baseUrl = "https://books.example",
                detectedVersion = "2.36.0",
                isFixture = false,
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
                username = "kari",
                displayName = "Kari",
                role = "Listener",
                requiresReauthentication = false,
                lastUsedAt = null,
                isFixture = false,
                accessibleLibrariesJson = "[]",
                hasAllLibraryAccess = true,
                hasAllTagAccess = true,
                canDownload = false,
            ),
        )
    }

    /**
     * A gateway that records what it was asked to send.
     *
     * A hand-written fake rather than a mock (PRODUCT_SPEC 17.1), and it records *arguments* because most of
     * what these tests assert is about what went on the wire: that a rewind arrived as a rewind, and that a
     * retry carried the same id.
     */
    private class RecordingPlaybackGateway :
        AudiobookshelfGateway,
        PlaybackApi {
        /**
         * PRODUCT_SPEC PLAY-003 — not part of this test, and refusing rather than pretending.
         *
         * The session *history* read is `ServerSessionHistoryTest`'s subject. A fake that answered with an
         * empty list here would let a future change to this file's subject silently depend on it.
         */
        override suspend fun listeningSessions(
            profileId: ProfileId,
            page: Int,
            itemsPerPage: Int,
        ): AppResult<List<ListeningSession>> = error("the session history read is not exercised here")

        var syncResult: AppResult<Unit> = AppResult.Success(Unit)
        var offlineUpload: AppResult<List<OfflineSessionResult>>? = null
        var offlineResults: (List<OfflineSession>) -> List<OfflineSessionResult> = { emptyList() }

        val syncedProgress = mutableListOf<SessionProgress>()
        val uploaded = mutableListOf<List<OfflineSession>>()

        /**
         * How many times the *request* was made, whether or not it succeeded.
         *
         * `syncedProgress` counts accepted writes; these count attempts, which is the question SYNC-002's
         * redundant-sync rule turns on. A sync that was skipped never reaches the gateway at all, and a
         * fake that only recorded successes could not tell that apart from one that failed.
         */
        var syncCount: Int = 0
            private set

        var closeCount: Int = 0
            private set

        override val playback: PlaybackApi get() = this

        /** PRODUCT_SPEC 11.1 — not part of these tests; every method reports so rather than pretending. */
        /**
         * PRODUCT_SPEC DL-001 — not exercised by this test, and refusing rather than pretending.
         *
         * A fake that produced bytes would let a test believe a download had happened, which is precisely the
         * belief the download layer exists to make impossible.
         */
        override val downloads: DownloadApi = object : DownloadApi {
            override suspend fun fetchFile(
                profileId: ProfileId,
                bookId: LibraryItemId,
                fileId: String,
                sink: (Boolean) -> OutputStream,
                resumeFrom: Long,
                validator: String?,
                onProgress: (Long) -> Unit,
            ): AppResult<FileTransfer> = AppResult.Failure(
                AppError.ApiCompatibility(summary = "This fake serves no audio files."),
            )

            override suspend fun fetchCover(
                profileId: ProfileId,
                bookId: LibraryItemId,
                sink: () -> OutputStream,
            ): AppResult<String?> = AppResult.Failure(
                AppError.ApiCompatibility(summary = "This fake serves no cover art."),
            )
        }

        override val bookmarks: BookmarkApi = object : BookmarkApi {
            override suspend fun create(
                profileId: ProfileId,
                bookId: LibraryItemId,
                at: Duration,
                title: String,
            ): AppResult<Bookmark> = unsupported()

            override suspend fun rename(
                profileId: ProfileId,
                bookId: LibraryItemId,
                at: Duration,
                title: String,
            ): AppResult<Bookmark> = unsupported()

            override suspend fun remove(profileId: ProfileId, bookId: LibraryItemId, at: Duration): AppResult<Unit> =
                unsupported()
        }

        override suspend fun openSession(profileId: ProfileId, bookId: LibraryItemId): AppResult<PlaybackSession> =
            unsupported()

        override suspend fun serverProgress(profileId: ProfileId, bookId: LibraryItemId): AppResult<ServerProgress> =
            unsupported()

        override suspend fun syncSession(
            profileId: ProfileId,
            sessionId: String,
            progress: SessionProgress,
        ): AppResult<Unit> {
            syncCount += 1
            if (syncResult is AppResult.Success) syncedProgress += progress
            return syncResult
        }

        override suspend fun closeSession(
            profileId: ProfileId,
            sessionId: String,
            progress: SessionProgress,
        ): AppResult<Unit> {
            closeCount += 1
            if (syncResult is AppResult.Success) syncedProgress += progress
            return syncResult
        }

        override suspend fun syncOfflineSessions(
            profileId: ProfileId,
            sessions: List<OfflineSession>,
        ): AppResult<List<OfflineSessionResult>> {
            uploaded += sessions
            return offlineUpload ?: AppResult.Success(offlineResults(sessions))
        }

        override suspend fun setFinished(
            profileId: ProfileId,
            bookId: LibraryItemId,
            isFinished: Boolean,
            position: kotlin.time.Duration,
        ): AppResult<Unit> = unsupported()

        override val auth: AuthApi = object : AuthApi {
            override suspend fun probe(serverUrl: String): AppResult<ServerProbe> = unsupported()

            override suspend fun signIn(
                serverUrl: String,
                username: String,
                password: String,
            ): AppResult<AuthSession> = unsupported()

            override suspend fun refresh(serverUrl: String, refreshToken: AuthToken): AppResult<AuthSession> =
                unsupported()

            override suspend fun currentAccount(serverUrl: String, accessToken: AuthToken): AppResult<AccountState> =
                unsupported()

            override suspend fun signOut(serverUrl: String, accessToken: AuthToken): AppResult<Unit> = unsupported()
        }

        override val capabilities: CapabilityResolver = object : CapabilityResolver {
            override suspend fun resolve(
                serverId: ServerId,
                serverUrl: String,
                accessToken: AuthToken?,
            ): AppResult<ServerCapabilities> = unsupported()
        }

        /** PRODUCT_SPEC EPIC MGR — not exercised here. A throwing property cannot fall behind the interface. */
        override val management: ManagementApi get() = error("management is not exercised here")

        override val library: LibraryApi = object : LibraryApi {
            override suspend fun listLibraries(profileId: ProfileId): AppResult<List<Library>> = unsupported()

            override suspend fun listAuthors(profileId: ProfileId, libraryId: LibraryId): AppResult<List<Author>> =
                unsupported()

            override suspend fun listBooks(
                profileId: ProfileId,
                libraryId: LibraryId,
                onBatch: suspend (List<BookSnapshot>) -> Unit,
                cached: CachedLibrary,
                onCatalogueBatch: suspend (List<BookSnapshot>) -> Unit,
            ): AppResult<LibrarySnapshot> = unsupported()

            override suspend fun searchBooks(
                profileId: ProfileId,
                libraryId: LibraryId,
                query: String,
            ): AppResult<List<BookSnapshot>> = unsupported()
            override suspend fun fetchBook(profileId: ProfileId, bookId: LibraryItemId): AppResult<BookSnapshot> =
                unsupported()
        }

        private companion object {
            fun <T> unsupported(): AppResult<T> =
                AppResult.Failure(AppError.ApiCompatibility(summary = "not part of this fake"))
        }
    }

    private companion object {
        const val SERVER = "server-1"
        const val BOOK_TITLE = "The Salt Harbour"

        /** The key `openSession` derives for `book-1`, which the progress row has to share. */
        val BOOK_KEY: String = EntityKey.of(SERVER, "book-1")
        val LIBRARY_KEY: String = EntityKey.of(SERVER, "lib-1")
        const val BOOK_AUTHOR = "Marisol Holt"
    }
}
