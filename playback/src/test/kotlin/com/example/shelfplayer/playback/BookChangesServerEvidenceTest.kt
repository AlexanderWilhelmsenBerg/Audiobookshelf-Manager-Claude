package com.example.shelfplayer.playback

import android.content.Context
import androidx.media3.common.ForwardingPlayer
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.PlayableTrack
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.model.playback.SessionProgress
import com.example.shelfplayer.core.model.playback.SessionSyncDiagnostics
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.core.model.playback.SyncOutcome
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.playback.ResumeBaseline
import com.example.shelfplayer.domain.realtime.RealtimeProgressEvidenceStore
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.SessionSyncRepository
import com.example.shelfplayer.domain.repository.SleepTimerRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Issue #91 — only a real server `/play` result may become an acknowledged start baseline.
 *
 * These tests intentionally cross the real [BookChanges.onBookOpened] boundary and then perform the same
 * [ResumeBaseline.onBookClosed] transition the service performs when Media3 installs the item. If
 * [BookChanges] ever stages `MediaItems.serverStartPositionFor(session)` directly again, the offline case
 * fails because a blank-id local session would be promoted as server evidence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class BookChangesServerEvidenceTest {

    @Test
    fun `blank id offline open cannot become acknowledged server evidence after media transition`() = runTest {
        val baseline = ResumeBaseline()
        val changes = bookChanges(baseline).changes

        changes.onBookOpened(session(id = ""))
        baseline.onBookClosed()

        assertNull(baseline.acknowledged(BOOK))
    }

    @Test
    fun `real server open becomes acknowledged server evidence after media transition`() = runTest {
        val baseline = ResumeBaseline()
        val changes = bookChanges(baseline).changes

        changes.onBookOpened(session(id = "remote-session"))
        baseline.onBookClosed()

        assertEquals(START, baseline.acknowledged(BOOK)?.position)
    }

    @Test
    fun `rejected durable car open leaves the current book timer and baseline intact`() = runTest {
        val sessions = HoldingSessions()
        val baseline = ResumeBaseline()
        val boundary = bookChanges(baseline, sessions)
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        try {
            val old = session("old")
            player.setMediaItem(MediaItems.queueFor(old).item, START.inWholeMilliseconds)
            boundary.sync.attach(player)
            boundary.timer.attach(object : ForwardingPlayer(player) {
                // Model audible playback; this case verifies commit ordering rather than decoding.
                override fun isPlaying(): Boolean = true
            })
            boundary.changes.onBookOpened(old)
            baseline.onBookClosed()
            boundary.timer.start(SleepTimerMode.Fixed(15.minutes))
            val oldTimer = boundary.timer.state.value
            var authorized = true
            var installed = false
            val accepting = async(start = CoroutineStart.UNDISPATCHED) {
                boundary.changes.acceptBook(session("new", LibraryItemId("book-b")), { authorized }) {
                    installed = true
                }
            }
            sessions.entered.await()
            authorized = false
            sessions.release.complete(Unit)

            assertFalse(accepting.await())
            assertFalse(installed)
            assertEquals(oldTimer, boundary.timer.state.value)
            baseline.onBookClosed()
            assertNull(baseline.acknowledged(LibraryItemId("book-b")))
            boundary.sync.sync(SyncTrigger.Paused)
            assertEquals("local-old", sessions.lastSynced)
            assertEquals(0, sessions.closed)
        } finally {
            boundary.timer.attach(null)
            boundary.sync.attach(null)
            player.release()
        }
    }

    @Test
    fun `accepted car book changes the timer before the player install callback without suspension`() = runTest {
        val baseline = ResumeBaseline()
        val boundary = bookChanges(baseline)
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        try {
            val old = session("old")
            player.setMediaItem(MediaItems.queueFor(old).item, START.inWholeMilliseconds)
            boundary.timer.attach(object : ForwardingPlayer(player) {
                override fun isPlaying(): Boolean = true
            })
            boundary.changes.onBookOpened(old)
            boundary.timer.start(SleepTimerMode.Fixed(15.minutes))
            assertTrue(boundary.timer.state.value != SleepTimerState.Idle)
            val incoming = session("remote", LibraryItemId("book-b"))
            var installed = false
            assertTrue(
                boundary.changes.acceptBook(incoming, { true }) {
                    assertEquals(SleepTimerState.Idle, boundary.timer.state.value)
                    baseline.onBookClosed()
                    assertEquals(START, baseline.acknowledged(incoming.bookId)?.position)
                    player.setMediaItem(MediaItems.queueFor(incoming).item)
                    installed = true
                },
            )
            assertTrue(installed)
        } finally {
            boundary.timer.attach(null)
            player.release()
        }
    }

    private fun TestScope.bookChanges(
        baseline: ResumeBaseline,
        sessions: SessionSyncRepository = proxy<SessionSyncRepository> { name ->
            if (name == "openSession") AppResult.Success("local-session") else null
        },
    ): Boundary {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val clock = TestAppClock()
        val sync = SessionSyncCoordinator(
            repository = sessions,
            baseline = baseline,
            clock = clock,
            logger = NO_OP_LOGGER,
            applicationScope = backgroundScope,
            mainDispatcher = dispatcher,
        )
        val history = proxy<PlaybackHistoryRepository>()
        val sleepTimer = SleepTimerController(
            repository = timerRepository(),
            shakes = ShakeDetector(ApplicationProvider.getApplicationContext<Context>(), NO_OP_LOGGER),
            sessionSync = sync,
            history = history,
            clock = clock,
            zoneProvider = object : LocalZoneProvider {
                override fun current() = java.time.ZoneOffset.UTC
            },
            logger = NO_OP_LOGGER,
            applicationScope = backgroundScope,
            mainDispatcher = dispatcher,
        )
        val autoRewind = AutoRewindController(
            repository = proxy<PlaybackSettingsRepository> { name ->
                if (name == "observeSettings") flowOf(PlaybackSettings()) else null
            },
            clock = clock,
            history = history,
            logger = NO_OP_LOGGER,
            applicationScope = backgroundScope,
        )
        val freshness = ResumeFreshnessCoordinator(
            playback = proxy<PlaybackRepository>(),
            profiles = proxy<ProfileRepository>(),
            baseline = baseline,
            realtime = RealtimeProgressEvidenceStore(),
            logger = NO_OP_LOGGER,
            applicationScope = backgroundScope,
            mainDispatcher = dispatcher,
        )
        val changes = BookChanges(
            sleepTimer = sleepTimer,
            sessionSync = sync,
            autoRewind = autoRewind,
            resumeBaseline = baseline,
            resumeFreshness = freshness,
            listeningHistory = ListeningHistoryRecorder(history),
        )
        return Boundary(changes, sleepTimer, sync)
    }

    private fun timerRepository(): SleepTimerRepository = proxy { name ->
        when (name) {
            "observeSettings" -> flowOf(SleepTimerSettings.Default)
            "recordStarted" -> AppResult.Success("timer-session")
            "recordEnded" -> AppResult.Success(Unit)
            "closeOrphanedSessions" -> AppResult.Success(0)
            else -> null
        }
    }

    private fun session(id: String, bookId: LibraryItemId = BOOK) = PlaybackSession(
        id = id,
        profileId = PROFILE,
        bookId = bookId,
        title = "Test book",
        author = null,
        coverUrl = null,
        startAt = START,
        duration = 8.hours,
        tracks = listOf(
            PlayableTrack(
                index = 0,
                url = "file:///book.m4b",
                startOffset = Duration.ZERO,
                duration = 8.hours,
                mimeType = "audio/mp4",
                isExcluded = false,
            ),
        ),
        chapters = emptyList(),
    )

    private data class Boundary(
        val changes: BookChanges,
        val timer: SleepTimerController,
        val sync: SessionSyncCoordinator,
    )

    private class HoldingSessions : SessionSyncRepository {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var lastSynced: String? = null
        var closed = 0

        override suspend fun openSession(session: PlaybackSession, startedAt: Instant): AppResult<String> {
            if (session.id == "new") {
                entered.complete(Unit)
                release.await()
            }
            return AppResult.Success("local-${session.id}")
        }

        override suspend fun syncOpenSession(
            sessionId: String,
            progress: SessionProgress,
            updatedAt: Instant,
            trigger: SyncTrigger,
        ): AppResult<SyncOutcome> {
            lastSynced = sessionId
            return AppResult.Success(SyncOutcome.Accepted)
        }

        override suspend fun closeSession(
            sessionId: String,
            progress: SessionProgress,
            updatedAt: Instant,
            trigger: SyncTrigger,
        ): AppResult<SyncOutcome> {
            closed += 1
            return AppResult.Success(SyncOutcome.Accepted)
        }

        override suspend fun drainOutbox(): AppResult<Int> = AppResult.Success(0)

        override fun observeDiagnostics(): Flow<SessionSyncDiagnostics> = emptyFlow()
    }

    private inline fun <reified T : Any> proxy(crossinline answer: (String) -> Any? = { null }): T =
        Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ -> answer(method.name.substringBefore('-')) ?: defaultValue(method.returnType) } as T

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private companion object {
        val PROFILE = ProfileId("profile-a")
        val BOOK = LibraryItemId("book-a")
        val START: Duration = 42.minutes
        val NO_OP_LOGGER = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
    }
}
