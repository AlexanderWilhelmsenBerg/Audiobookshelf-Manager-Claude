package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.PlaybackHistoryEntry
import com.example.shelfplayer.core.model.playback.SessionProgress
import com.example.shelfplayer.core.model.playback.SessionSyncDiagnostics
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerOutcome
import com.example.shelfplayer.core.model.playback.SleepTimerScheduleSettings
import com.example.shelfplayer.core.model.playback.SleepTimerSession
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.core.model.playback.SyncOutcome
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.playback.ResumeBaseline
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.SessionSyncRepository
import com.example.shelfplayer.domain.repository.SleepTimerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerControllerTest {

    @Test
    fun `a late enabled setting starts sensing and a shake refreshes the active timer`() = runTest {
        val settings = MutableSharedFlow<SleepTimerSettings>(extraBufferCapacity = 4)
        val repository = FakeSleepTimerRepository(settings)
        val shakes = FakeShakeSource()
        val clock = TestAppClock()
        val controller = controller(repository, shakes, clock)
        controller.attach(player())

        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(30.minutes)))
        assertFalse(shakes.isSensing)

        settings.emit(SleepTimerSettings.Default.copy(shakeToRestart = true))
        runCurrent()

        assertTrue(shakes.isSensing)
        assertEquals(1, shakes.startCalls)

        clock.advanceBy(5.minutes)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(25.minutes, controller.state.value.remaining)

        shakes.fire()
        runCurrent()

        assertEquals(30.minutes, controller.state.value.remaining)
        assertEquals(1, repository.started)
        assertEquals(1, repository.restarted)
    }

    @Test
    fun `repeated shakes reuse one timer and settings changes reconcile one listener`() = runTest {
        val settings = MutableSharedFlow<SleepTimerSettings>(extraBufferCapacity = 8)
        val repository = FakeSleepTimerRepository(settings)
        val shakes = FakeShakeSource()
        val controller = controller(repository, shakes, TestAppClock())
        controller.attach(player())

        settings.emit(SleepTimerSettings.Default.copy(shakeToRestart = true))
        runCurrent()
        assertFalse(shakes.isSensing, "an opt-in alone must not run the accelerometer")

        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(30.minutes)))
        assertTrue(shakes.isSensing)
        assertEquals(1, shakes.startCalls)

        shakes.fire()
        runCurrent()
        shakes.fire()
        runCurrent()

        assertEquals(1, repository.started, "shakes must not create another timer session")
        assertEquals(2, repository.restarted)
        assertEquals(1, shakes.startCalls, "valid shakes must not re-register the listener")

        settings.emit(SleepTimerSettings.Default.copy(shakeToRestart = true))
        runCurrent()
        assertEquals(1, shakes.startCalls, "a repeated enabled setting must be idempotent")

        settings.emit(SleepTimerSettings.Default.copy(shakeToRestart = false))
        runCurrent()
        assertFalse(shakes.isSensing)
        assertEquals(1, shakes.stopCalls)

        settings.emit(SleepTimerSettings.Default.copy(shakeToRestart = true))
        runCurrent()
        assertTrue(shakes.isSensing)
        assertEquals(2, shakes.startCalls)
    }

    @Test
    fun `playback starting inside an overnight window arms the ordinary default timer`() = runTest {
        val schedule = SleepTimerScheduleSettings.Default.copy(enabled = true)
        val source = MutableStateFlow(SleepTimerSettings.Default.copy(schedule = schedule))
        val repository = FakeSleepTimerRepository(source)
        val clock = TestAppClock(Instant.parse("2026-09-19T23:00:00Z"))
        val controller = controller(repository, FakeShakeSource(), clock)
        controller.attach(player())
        runCurrent()

        controller.onPlayRequest(explicit = true)
        controller.onPlaybackChanged(isPlaying = true)
        runCurrent()

        assertIs<SleepTimerMode.Fixed>(controller.state.value.mode)
        assertEquals(SleepTimerSettings.Default.defaultLength, controller.state.value.remaining)
        assertEquals(1, repository.started)
    }

    @Test
    fun `active playback crossing start boundary arms while the end boundary never truncates`() = runTest {
        val schedule = SleepTimerScheduleSettings.Default.copy(enabled = true)
        val source = MutableStateFlow(SleepTimerSettings.Default.copy(schedule = schedule))
        val repository = FakeSleepTimerRepository(source)
        val clock = TestAppClock(Instant.parse("2026-09-19T21:59:00Z"))
        val controller = controller(repository, FakeShakeSource(), clock)
        controller.attach(player())
        runCurrent()

        controller.onPlaybackChanged(isPlaying = true)
        runCurrent()
        assertEquals(SleepTimerState.Idle, controller.state.value)

        clock.advanceBy(1.minutes)
        advanceTimeBy(60_001)
        runCurrent()
        assertTrue(controller.state.value.isActive)
        assertEquals(1, repository.started)
    }

    @Test
    fun `automatic timer started just before schedule end is cancelled at the boundary without expiring`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                defaultLength = 15.minutes,
                fadeLength = Duration.ZERO,
                schedule = SleepTimerScheduleSettings.Default.copy(
                    enabled = true,
                    start = java.time.LocalTime.of(22, 0),
                    end = java.time.LocalTime.of(7, 0),
                ),
            ),
        )
        val repository = FakeSleepTimerRepository(source)
        val clock = TestAppClock(Instant.parse("2026-09-20T06:46:00Z"))
        val controller = controller(repository, FakeShakeSource(), clock)
        controller.attach(player())
        runCurrent()

        controller.onPlaybackChanged(isPlaying = true)
        runCurrent()
        assertEquals(15.minutes, controller.state.value.remaining)

        clock.advanceBy(14.minutes)
        advanceTimeBy(14.minutes.inWholeMilliseconds + 1)
        runCurrent()

        assertEquals(SleepTimerState.Idle, controller.state.value)
        assertEquals(SleepTimerOutcome.Cancelled, repository.ended.last())
        assertEquals(null, repository.replayRequiredOccurrence)
        assertEquals(null, repository.suppressedOccurrence)
    }

    @Test
    fun `manual timer replacing automatic timer survives the schedule end`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                defaultLength = 15.minutes,
                schedule = SleepTimerScheduleSettings.Default.copy(
                    enabled = true,
                    start = java.time.LocalTime.of(22, 0),
                    end = java.time.LocalTime.of(7, 0),
                ),
            ),
        )
        val repository = FakeSleepTimerRepository(source)
        val clock = TestAppClock(Instant.parse("2026-09-20T06:46:00Z"))
        val controller = controller(repository, FakeShakeSource(), clock)
        controller.attach(player())
        runCurrent()

        controller.onPlaybackChanged(isPlaying = true)
        runCurrent()
        assertTrue(controller.state.value.isActive)

        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(20.minutes)))
        runCurrent()

        clock.advanceBy(14.minutes)
        advanceTimeBy(14.minutes.inWholeMilliseconds + 1)
        runCurrent()

        assertEquals(SleepTimerMode.Fixed(20.minutes), controller.state.value.mode)
        assertTrue(controller.state.value.isActive)
    }

    @Test
    fun `manual timer replaces automatic timer without suppressing the window`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                schedule = SleepTimerScheduleSettings.Default.copy(enabled = true),
            ),
        )
        val repository = FakeSleepTimerRepository(source)
        val clock = TestAppClock(Instant.parse("2026-09-19T23:00:00Z"))
        val controller = controller(repository, FakeShakeSource(), clock)
        controller.attach(player())
        runCurrent()
        controller.onPlaybackChanged(isPlaying = true)
        runCurrent()

        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(45.minutes)))
        runCurrent()

        assertEquals(SleepTimerMode.Fixed(45.minutes), controller.state.value.mode)
        assertEquals(null, repository.suppressedOccurrence)
        assertEquals(2, repository.started)
    }

    @Test
    fun `manual cancellation survives recreation and resets next night`() = runTest {
            val source = MutableStateFlow(
                SleepTimerSettings.Default.copy(
                    schedule = SleepTimerScheduleSettings.Default.copy(enabled = true),
                ),
            )
            val repository = FakeSleepTimerRepository(source)
            val clock = TestAppClock(Instant.parse("2026-09-19T23:00:00Z"))
            val first = controller(repository, FakeShakeSource(), clock)
            first.attach(player())
            runCurrent()
            first.onPlaybackChanged(isPlaying = true)
            runCurrent()
            assertTrue(first.state.value.isActive)

            first.cancel()
            runCurrent()
            val suppressed = assertNotNull(repository.suppressedOccurrence)
            assertEquals(SleepTimerState.Idle, first.state.value)

            first.onWallClockChanged()
            runCurrent()
            assertEquals(1, repository.started, "continuing playback must not recreate the cancelled timer")

            first.attach(null)
            val recreated = controller(repository, FakeShakeSource(), clock)
            recreated.attach(player())
            runCurrent()
            recreated.onPlaybackChanged(isPlaying = true)
            runCurrent()
            assertEquals(SleepTimerState.Idle, recreated.state.value)
            assertEquals(suppressed, repository.suppressedOccurrence)

            clock.setWallClock(Instant.parse("2026-09-20T23:00:00Z"))
            recreated.onWallClockChanged()
            runCurrent()
            assertTrue(recreated.state.value.isActive)
            assertEquals(2, repository.started)
            assertEquals(null, repository.suppressedOccurrence)
        }

    @Test
    fun `automatic expiry requires explicit replay before same occurrence can rearm`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                defaultLength = 5.minutes,
                fadeLength = Duration.ZERO,
                schedule = SleepTimerScheduleSettings.Default.copy(enabled = true),
            ),
        )
        val repository = FakeSleepTimerRepository(source)
        val clock = TestAppClock(Instant.parse("2026-09-19T23:00:00Z"))
        val controller = controller(repository, FakeShakeSource(), clock)
        controller.attach(player())
        runCurrent()
        controller.onPlaybackChanged(isPlaying = true)
        runCurrent()

        clock.advanceBy(5.minutes)
        advanceTimeBy(5.minutes.inWholeMilliseconds + 1_001)
        runCurrent()
        controller.onPlaybackChanged(isPlaying = false)
        runCurrent()

        assertEquals(SleepTimerState.Idle, controller.state.value)
        assertNotNull(repository.replayRequiredOccurrence)

        controller.onPlayRequest(explicit = false)
        runCurrent()
        controller.onPlaybackChanged(isPlaying = true)
        runCurrent()
        assertEquals(SleepTimerState.Idle, controller.state.value)
        assertEquals(1, repository.started)

        controller.onPlaybackChanged(isPlaying = false)
        runCurrent()
        controller.onPlayRequest(explicit = true)
        runCurrent()
        controller.onPlaybackChanged(isPlaying = true)
        runCurrent()

        assertTrue(controller.state.value.isActive)
        assertEquals(2, repository.started)
        assertEquals(null, repository.replayRequiredOccurrence)
    }

    @Test
    fun `cancelling the timer tears down sensing and leaves idle state`() = runTest {
        val settings = MutableSharedFlow<SleepTimerSettings>(extraBufferCapacity = 4)
        val repository = FakeSleepTimerRepository(settings)
        val shakes = FakeShakeSource()
        val controller = controller(repository, shakes, TestAppClock())
        controller.attach(player())

        settings.emit(SleepTimerSettings.Default.copy(shakeToRestart = true))
        runCurrent()
        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(30.minutes)))
        assertTrue(shakes.isSensing)

        controller.cancel()
        runCurrent()

        assertEquals(SleepTimerState.Idle, controller.state.value)
        assertFalse(shakes.isSensing)
        assertEquals(listOf(SleepTimerOutcome.Cancelled), repository.ended)

        settings.emit(SleepTimerSettings.Default.copy(shakeToRestart = true))
        runCurrent()
        assertEquals(1, shakes.startCalls, "no timer means an enabled setting cannot restart sensing")
    }

    private fun TestScope.controller(
        repository: SleepTimerRepository,
        shakes: ShakeSource,
        clock: TestAppClock,
    ): SleepTimerController {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sync = SessionSyncCoordinator(
            repository = FakeSessionSyncRepository(),
            baseline = ResumeBaseline(),
            clock = clock,
            logger = NO_OP_LOGGER,
            applicationScope = backgroundScope,
            mainDispatcher = dispatcher,
        )
        return SleepTimerController(
            repository = repository,
            shakes = shakes,
            sessionSync = sync,
            history = FakePlaybackHistoryRepository(),
            clock = clock,
            zoneProvider = object : LocalZoneProvider {
                override fun current() = java.time.ZoneOffset.UTC
            },
            logger = NO_OP_LOGGER,
            applicationScope = backgroundScope,
            mainDispatcher = dispatcher,
        )
    }

    private fun player(): Player {
        val item = MediaItem.Builder().setMediaId("book-a").build()
        return Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getCurrentMediaItem" -> item
                "getCurrentPosition" -> 10.minutes.inWholeMilliseconds
                "getDuration" -> 60.minutes.inWholeMilliseconds
                "getVolume" -> 1f
                "isPlaying" -> true
                else -> defaultValue(method.returnType)
            }
        } as Player
    }

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

    private class FakeShakeSource : ShakeSource {
        override var isSensing: Boolean = false
            private set
        var startCalls = 0
            private set
        var stopCalls = 0
            private set
        private var callback: (() -> Unit)? = null

        override fun start(onShake: () -> Unit): Boolean {
            startCalls += 1
            callback = onShake
            isSensing = true
            return true
        }

        override fun stop() {
            if (isSensing) stopCalls += 1
            callback = null
            isSensing = false
        }

        fun fire() {
            check(isSensing)
            callback?.invoke()
        }
    }

    private class FakeSleepTimerRepository(private val settings: Flow<SleepTimerSettings>) :
        SleepTimerRepository {
        var started = 0
            private set
        var restarted = 0
            private set
        val ended = mutableListOf<SleepTimerOutcome>()
        var suppressedOccurrence: String? = null
            private set
        var replayRequiredOccurrence: String? = null
            private set

        override fun observeSettings(): Flow<SleepTimerSettings> = settings

        override suspend fun setDefaultLength(length: Duration): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun setFadeLength(length: Duration): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun setShakeToRestart(enabled: Boolean): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun setRewindOnStop(length: Duration): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun setScheduleEnabled(enabled: Boolean): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun setScheduleWindow(
            start: java.time.LocalTime,
            end: java.time.LocalTime,
        ): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun setScheduleRuntimeState(
            suppressedOccurrence: String?,
            replayRequiredOccurrence: String?,
        ): AppResult<Unit> {
            this.suppressedOccurrence = suppressedOccurrence
            this.replayRequiredOccurrence = replayRequiredOccurrence
            (settings as? MutableStateFlow<SleepTimerSettings>)?.let { source ->
                source.value = source.value.copy(
                    schedule = source.value.schedule.copy(
                        suppressedOccurrence = suppressedOccurrence,
                        replayRequiredOccurrence = replayRequiredOccurrence,
                    ),
                )
            }
            return AppResult.Success(Unit)
        }

        override fun observeRecentSessions(limit: Int): Flow<List<SleepTimerSession>> = flowOf(emptyList())

        override suspend fun recordStarted(bookId: LibraryItemId, mode: SleepTimerMode): AppResult<String> {
            started += 1
            return AppResult.Success("timer-$started")
        }

        override suspend fun recordRestarted(sessionId: String): AppResult<Unit> {
            restarted += 1
            return AppResult.Success(Unit)
        }

        override suspend fun recordEnded(sessionId: String, outcome: SleepTimerOutcome): AppResult<Unit> {
            ended += outcome
            return AppResult.Success(Unit)
        }

        override suspend fun closeOrphanedSessions(): AppResult<Int> = AppResult.Success(0)
    }

    private class FakePlaybackHistoryRepository : PlaybackHistoryRepository {
        override fun observe(bookId: LibraryItemId, limit: Int): Flow<List<PlaybackHistoryEntry>> = flowOf(emptyList())

        override suspend fun record(
            bookId: LibraryItemId,
            event: PlaybackEvent,
            from: Duration?,
            to: Duration,
            detail: Duration?,
            at: Instant?,
            owner: ProfileId?,
        ) = Unit

        override suspend fun refreshServerSessions(bookId: LibraryItemId) = Unit

        override suspend fun clear(bookId: LibraryItemId) = Unit
    }

    private class FakeSessionSyncRepository : SessionSyncRepository {
        override suspend fun openSession(
            bookId: LibraryItemId,
            remoteSessionId: String?,
            title: String,
            author: String?,
            position: Duration,
            duration: Duration,
            startedAt: Instant,
        ): AppResult<String> = AppResult.Success("session")

        override suspend fun syncOpenSession(
            sessionId: String,
            progress: SessionProgress,
            updatedAt: Instant,
            trigger: SyncTrigger,
        ): AppResult<SyncOutcome> = AppResult.Success(SyncOutcome.Accepted)

        override suspend fun closeSession(
            sessionId: String,
            progress: SessionProgress,
            updatedAt: Instant,
            trigger: SyncTrigger,
        ): AppResult<SyncOutcome> = AppResult.Success(SyncOutcome.Accepted)

        override suspend fun drainOutbox(): AppResult<Int> = AppResult.Success(0)

        override fun observeDiagnostics(): Flow<SessionSyncDiagnostics> = emptyFlow()
    }

    private companion object {
        val NO_OP_LOGGER = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
    }
}
