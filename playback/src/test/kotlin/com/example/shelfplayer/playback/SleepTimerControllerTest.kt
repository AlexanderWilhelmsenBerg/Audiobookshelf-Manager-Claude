package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.PlaybackHistoryEntry
import com.example.shelfplayer.core.model.playback.SessionProgress
import com.example.shelfplayer.core.model.playback.SessionSyncDiagnostics
import com.example.shelfplayer.core.model.playback.ShakeSensitivity
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/*
 * This regression suite intentionally keeps the timer state-machine races in one fixture so every test shares
 * the same fake clock, persistence, player, and suspension gates. Splitting it only to satisfy a line threshold
 * would duplicate those fixtures and make cross-transition coverage harder to audit.
 */
@Suppress("LargeClass")
@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerControllerTest {

    @Test
    fun `timer history snapshots the book position and time before asynchronous dispatch`() = runTest {
        val clock = TestAppClock()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val volume = PlaybackVolumeEffects(clock)
        val sync = SessionSyncCoordinator(
            repository = FakeSessionSyncRepository(),
            baseline = ResumeBaseline(),
            clock = clock,
            logger = NO_OP_LOGGER,
            volumeEffects = volume,
            applicationScope = backgroundScope,
            mainDispatcher = dispatcher,
        )
        val repository = FakePlaybackHistoryRepository()
        val effects = SleepTimerHistory(FakeSleepTimerRepository(), repository, sync, clock, backgroundScope)
        var bookId = "book-a"
        var position = 10.minutes
        val media = routeTestPlayer(bookId = { bookId }, position = { position }) {}
        val happenedAt = clock.now()

        effects.record(media, PlaybackEvent.SleepTimerStarted)
        bookId = "replacement"
        position = 20.minutes
        clock.advanceBy(30.seconds)
        runCurrent()

        assertEquals(
            HistorySnapshot(LibraryItemId("book-a"), 10.minutes, happenedAt, null),
            repository.snapshots.single(),
        )
    }

    @Test
    fun `queued unmute replay cannot authorize a replacement book`() = runTest {
        val clock = TestAppClock(Instant.parse("2026-09-19T23:00:00Z"))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val effects = PlaybackVolumeEffects(clock)
        val baseSchedule = SleepTimerScheduleSettings.Default.copy(enabled = true)
        val occurrence =
            requireNotNull(SleepSchedulePolicy.currentOccurrence(clock.now(), ZoneOffset.UTC, baseSchedule))
        val settings = MutableStateFlow(
            SleepTimerSettings.Default.copy(schedule = baseSchedule.copy(replayRequiredOccurrence = occurrence.id)),
        )
        val repository = FakeSleepTimerRepository(settings)
        val controller = controller(
            repository,
            FakeShakeSource(),
            clock,
            volumeEffects = effects,
            dispatcher = dispatcher,
        )
        var bookId = "book-a"
        val sharedPlayer = routeTestPlayer(bookId = { bookId }) {}
        val guard = routeTestGuard(sharedPlayer, effects, dispatcher)
        guard.attachPlayer(sharedPlayer)
        controller.attach(sharedPlayer)
        runCurrent()
        assertTrue(guard.beginProbe("headset", sharedPlayer, 5L))
        val requested = launch { controller.onPlayRequest(explicit = true) }
        runCurrent()
        requested.join()
        controller.onPlaybackChanged(true)
        runCurrent()

        guard.verifyProbeIfActive(setOf("headset"), emptySet(), isPlaying = true)
        controller.onBookChangedImmediately(emptyList())
        bookId = "book-b"
        runCurrent()

        assertEquals(0, repository.started, "the old book's queued unmute must not start a replacement timer")
        assertEquals(occurrence.id, settings.value.schedule.replayRequiredOccurrence)
        assertFalse(controller.state.value.isActive)
    }

    @Test
    fun `pause during a suspended start retires its provisional session`() = runTest {
        val repository = FakeSleepTimerRepository()
        val controller = controller(repository, FakeShakeSource(), TestAppClock())
        var playing = true
        controller.attach(player(playing = { playing }))
        val gate = repository.blockNextRecordStarted()
        var result: AppResult<Unit>? = null
        val start = launch { result = controller.start(SleepTimerMode.Fixed(30.minutes)) }
        runCurrent()
        playing = false
        controller.onPlaybackChanged(false)
        gate.resume()
        start.join()
        assertIs<AppResult.Failure>(result)
        assertFalse(controller.state.value.isActive)
        assertEquals(1, repository.ended.count { it == SleepTimerOutcome.PlaybackStopped })
    }

    @Test
    fun `sleep tick cannot unmute an active route verification probe`() = runTest {
        val clock = TestAppClock()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val volumeEffects = PlaybackVolumeEffects(clock)
        val controller = controller(FakeSleepTimerRepository(), FakeShakeSource(), clock, volumeEffects = volumeEffects)
        var rawVolume = 1f
        val sharedPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getVolume" -> rawVolume

                "setVolume" -> {
                    rawVolume = args!![0] as Float
                    null
                }

                "isPlaying" -> true

                "getCurrentMediaItem" -> MediaItem.Builder().setMediaId("book-a").build()

                "getCurrentPosition" -> 10.minutes.inWholeMilliseconds

                "getDuration" -> 60.minutes.inWholeMilliseconds

                "addListener", "removeListener" -> null

                else -> defaultValue(method.returnType)
            }
        } as ExoPlayer
        val guard = HeadsetRouteGuard(
            scope = backgroundScope,
            playerDispatcher = dispatcher,
            volumeEffects = volumeEffects,
            isCurrentProbe = { _, player, _ -> player === sharedPlayer },
            onProbeInvalidated = { false },
            onProbeFinished = {},
            onPlaying = {},
        )
        guard.attachPlayer(sharedPlayer)
        controller.attach(sharedPlayer)
        controller.start(SleepTimerMode.Fixed(30.minutes))
        runCurrent()
        assertTrue(guard.beginProbe("headset", sharedPlayer, 1L))
        assertEquals(0f, rawVolume)

        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(0f, rawVolume, "a sleep fade/tick must not undo the route-owned mute")
    }

    @Test
    fun `route verification mute freezes a fixed timer without changing audible player state`() = runTest {
        val clock = TestAppClock()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val volumeEffects = PlaybackVolumeEffects(clock)
        val controller = controller(FakeSleepTimerRepository(), FakeShakeSource(), clock, volumeEffects = volumeEffects)
        var rawVolume = 1f
        val sharedPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getVolume" -> rawVolume

                "setVolume" -> {
                    rawVolume = args!![0] as Float
                    null
                }

                "isPlaying" -> true

                "getCurrentMediaItem" -> MediaItem.Builder().setMediaId("book-a").build()

                "getCurrentPosition" -> 10.minutes.inWholeMilliseconds

                "getDuration" -> 60.minutes.inWholeMilliseconds

                "addListener", "removeListener" -> null

                else -> defaultValue(method.returnType)
            }
        } as ExoPlayer
        val guard = HeadsetRouteGuard(
            scope = backgroundScope,
            playerDispatcher = dispatcher,
            volumeEffects = volumeEffects,
            isCurrentProbe = { _, player, _ -> player === sharedPlayer },
            onProbeInvalidated = { false },
            onProbeFinished = {},
            onPlaying = {},
        )
        guard.attachPlayer(sharedPlayer)
        controller.attach(sharedPlayer)
        controller.start(SleepTimerMode.Fixed(30.minutes))
        runCurrent()
        assertTrue(guard.beginProbe("headset", sharedPlayer, 1L))
        controller.onPlaybackChanged(true)

        clock.advanceBy(5.minutes)
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(30.minutes, controller.state.value.remaining)
        assertTrue(controller.state.value.isActive)
        assertEquals(0f, rawVolume)
    }

    @Test
    fun `mute preserves the latest fade gain until route proof ends`() = runTest {
        val clock = TestAppClock()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val effects = PlaybackVolumeEffects(clock)
        val settings = MutableStateFlow(SleepTimerSettings.Default.copy(fadeLength = 10.minutes))
        val controller =
            controller(FakeSleepTimerRepository(settings), FakeShakeSource(), clock, volumeEffects = effects)
        var rawVolume = 1f
        val sharedPlayer = routeTestPlayer { value -> rawVolume = value }
        val guard = routeTestGuard(sharedPlayer, effects, dispatcher)
        guard.attachPlayer(sharedPlayer)
        controller.attach(sharedPlayer)
        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(30.minutes)))
        runCurrent()

        clock.advanceBy(25.minutes)
        advanceTimeBy(1_001)
        runCurrent()
        val latestFadeGain = rawVolume
        assertTrue(latestFadeGain in 0f..1f && latestFadeGain < 1f)
        assertTrue(guard.beginProbe("headset", sharedPlayer, 2L))
        controller.onPlaybackChanged(true)
        clock.advanceBy(5.minutes)
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(5.minutes, controller.state.value.remaining)
        assertEquals(0f, rawVolume)
        assertTrue(guard.verifyProbeIfActive(setOf("headset"), emptySet(), isPlaying = true))
        runCurrent()
        assertEquals(latestFadeGain, rawVolume, "probe completion restores the latest requested sleep fade")
        assertEquals(5.minutes, controller.state.value.remaining)
    }

    @Test
    fun `muted raw playing callback preserves explicit replay marker until audible verification ends`() = runTest {
        val clock = TestAppClock(Instant.parse("2026-09-19T23:00:00Z"))
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val effects = PlaybackVolumeEffects(clock)
        val baseSchedule = SleepTimerScheduleSettings.Default.copy(enabled = true)
        val occurrence =
            requireNotNull(SleepSchedulePolicy.currentOccurrence(clock.now(), ZoneOffset.UTC, baseSchedule))
        val schedule = baseSchedule.copy(replayRequiredOccurrence = occurrence.id)
        val settings = MutableStateFlow(SleepTimerSettings.Default.copy(schedule = schedule))
        val repository = FakeSleepTimerRepository(settings)
        val controller = controller(repository, FakeShakeSource(), clock, volumeEffects = effects)
        var rawVolume = 1f
        val sharedPlayer = routeTestPlayer { value -> rawVolume = value }
        val guard = routeTestGuard(sharedPlayer, effects, dispatcher)
        guard.attachPlayer(sharedPlayer)
        controller.attach(sharedPlayer)
        assertTrue(guard.beginProbe("headset", sharedPlayer, 3L))
        assertIs<AppResult.Failure>(controller.start(SleepTimerMode.Fixed(30.minutes)))

        controller.onPlayRequest(explicit = true)
        controller.onPlaybackChanged(true)
        runCurrent()
        assertEquals(0, repository.started, "muted playback cannot arm a new timer")

        guard.verifyProbeIfActive(setOf("headset"), emptySet(), isPlaying = true)
        runCurrent()

        assertEquals(1, repository.started, "the deferred explicit Play authorizes the replay after verification")
        assertTrue(controller.state.value.isActive)
        assertTrue(rawVolume > 0f)
    }

    @Test
    fun `route mute invalidates a timer creation suspended in history persistence`() = runTest {
        val clock = TestAppClock()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val effects = PlaybackVolumeEffects(clock)
        val repository = FakeSleepTimerRepository()
        val controller = controller(repository, FakeShakeSource(), clock, volumeEffects = effects)
        var rawVolume = 1f
        val sharedPlayer = routeTestPlayer { value -> rawVolume = value }
        val guard = routeTestGuard(sharedPlayer, effects, dispatcher)
        guard.attachPlayer(sharedPlayer)
        controller.attach(sharedPlayer)
        val persistence = repository.blockNextRecordStarted()
        var result: AppResult<Unit>? = null
        val starting = launch { result = controller.start(SleepTimerMode.Fixed(30.minutes)) }
        persistence.awaitEntered()

        assertTrue(guard.beginProbe("headset", sharedPlayer, 4L))
        persistence.resume()
        starting.join()

        assertIs<AppResult.Failure>(result)
        assertFalse(controller.state.value.isActive)
        assertEquals(listOf(SleepTimerOutcome.PlaybackStopped), repository.ended)
        assertEquals(0f, rawVolume)
    }

    @Test
    fun `extending a paused timer preserves its frozen clock`() = runTest {
        val clock = TestAppClock()
        val controller = controller(FakeSleepTimerRepository(), FakeShakeSource(), clock)
        var playing = true
        controller.attach(player(playing = { playing }))
        controller.start(SleepTimerMode.Fixed(30.minutes))
        clock.advanceBy(5.minutes)
        playing = false
        controller.onPlaybackChanged(false)
        runCurrent()
        clock.advanceBy(40.minutes)
        controller.extend()
        runCurrent()
        assertEquals(55.minutes, controller.state.value.remaining)
        clock.advanceBy(10.minutes)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(55.minutes, controller.state.value.remaining)
    }

    @Test
    fun `paused audio freezes the fixed timer and resuming uses only the saved remainder`() = runTest {
        val clock = TestAppClock()
        val controller = controller(FakeSleepTimerRepository(), FakeShakeSource(), clock)
        var playing = true
        controller.attach(player(playing = { playing }))
        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(30.minutes)))
        clock.advanceBy(5.minutes)
        playing = false
        controller.onPlaybackChanged(false)
        runCurrent()
        assertEquals(25.minutes, controller.state.value.remaining)
        clock.advanceBy(40.minutes)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(25.minutes, controller.state.value.remaining)
        assertTrue(controller.state.value.isActive)
        playing = true
        controller.onPlaybackChanged(true)
        runCurrent()
        clock.advanceBy(1.minutes)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(24.minutes, controller.state.value.remaining)
    }

    @Test
    fun `manual sleep timer and history survive car focus pause without fabricated resume`() = runTest {
        val clock = TestAppClock()
        val repository = FakeSleepTimerRepository()
        val history = FakePlaybackHistoryRepository()
        val controller = controller(repository, FakeShakeSource(), clock, history)
        var playing = true
        controller.attach(player(playing = { playing }))
        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(30.minutes)))

        controller.onCarConnectionChanged(true)
        runCurrent()
        clock.advanceBy(5.minutes)
        playing = false
        controller.onPlaybackChanged(false)
        runCurrent()
        assertEquals(25.minutes, controller.state.value.remaining)

        clock.advanceBy(40.minutes)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(25.minutes, controller.state.value.remaining)
        assertTrue(controller.state.value.isActive, "a manual timer survives focus-related pause in the car")

        controller.onCarConnectionChanged(false)
        runCurrent()
        assertEquals(0, repository.restarted, "projection departure must not claim sleep-timer grace")
        assertEquals(
            listOf(PlaybackEvent.SleepTimerStarted),
            history.events,
            "car and focus events must not add another history entry after the legitimate timer start",
        )

        playing = true
        controller.onPlaybackChanged(true)
        runCurrent()
        clock.advanceBy(1.minutes)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(24.minutes, controller.state.value.remaining)
        assertEquals(1, repository.started)
        assertEquals(0, repository.restarted)
        assertEquals(listOf(PlaybackEvent.SleepTimerStarted), history.events)
    }

    @Test
    fun `new timer is refused for a paused book without writing a session`() = runTest {
        val repository = FakeSleepTimerRepository()
        val controller = controller(repository, FakeShakeSource(), TestAppClock())
        controller.attach(player(playing = { false }))
        assertIs<AppResult.Failure>(controller.start(SleepTimerMode.Fixed(30.minutes)))
        assertEquals(0, repository.started)
        assertFalse(controller.state.value.isActive)
    }

    @Test
    fun `new timer is refused with no loaded book even if player reports playing`() = runTest {
        val repository = FakeSleepTimerRepository()
        val controller = controller(repository, FakeShakeSource(), TestAppClock())
        controller.attach(player(hasBook = false))
        assertIs<AppResult.Failure>(controller.start(SleepTimerMode.Fixed(30.minutes)))
        assertEquals(0, repository.started)
    }

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
    fun `shake inside post-expiry grace restarts through the canonical resume owner`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                shakeToRestart = true,
                shakeGracePeriod = 10.seconds,
                fadeLength = Duration.ZERO,
            ),
        )
        val repository = FakeSleepTimerRepository(source)
        val shakes = FakeShakeSource()
        val clock = TestAppClock()
        var rawPlayCalls = 0
        var canonicalResumeCalls = 0
        val controller = controller(repository, shakes, clock)
        controller.attach(
            player(onPlay = { rawPlayCalls += 1 }),
            resumeOwner { canonicalResumeCalls += 1 },
        )
        runCurrent()

        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(1.seconds)))
        clock.advanceBy(1.seconds)
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(SleepTimerState.Idle, controller.state.value)
        assertTrue(shakes.isSensing, "the sensor remains only for the configured grace window")

        shakes.fire()
        runCurrent()

        assertTrue(controller.state.value.isActive)
        assertEquals(SleepTimerMode.Fixed(1.seconds), controller.state.value.mode)
        assertEquals(2, repository.started, "expiry closes one session and a grace shake starts another")
        assertEquals(1, canonicalResumeCalls)
        assertEquals(0, rawPlayCalls, "the timer must not bypass the service's resume-freshness owner")
    }

    @Test
    fun `shake restart and manual extension update timer journal without audiobook history events`() = runTest {
        val settings = MutableStateFlow(SleepTimerSettings.Default.copy(shakeToRestart = true))
        val repository = FakeSleepTimerRepository(settings)
        val shakes = FakeShakeSource()
        val history = FakePlaybackHistoryRepository()
        val controller = controller(repository, shakes, TestAppClock(), history)
        controller.attach(player())
        runCurrent()

        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(30.minutes)))
        runCurrent()
        assertEquals(listOf(PlaybackEvent.SleepTimerStarted), history.events)

        shakes.fire()
        runCurrent()
        controller.extend()
        runCurrent()

        assertEquals(1, repository.started)
        assertEquals(2, repository.restarted)
        assertEquals(listOf(PlaybackEvent.SleepTimerStarted), history.events)
        assertTrue(controller.state.value.isActive)
    }

    @Test
    fun `expired timer grace shake opens a timer session but no extra listening history marker`() = runTest {
        val settings = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                shakeToRestart = true,
                shakeGracePeriod = 10.seconds,
                fadeLength = Duration.ZERO,
            ),
        )
        val repository = FakeSleepTimerRepository(settings)
        val shakes = FakeShakeSource()
        val history = FakePlaybackHistoryRepository()
        val clock = TestAppClock()
        val controller = controller(repository, shakes, clock, history)
        controller.attach(player(), resumeOwner())
        runCurrent()

        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(1.seconds)))
        clock.advanceBy(1.seconds)
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(history.events.contains(PlaybackEvent.SleepTimerExpired))

        val eventsAtExpiry = history.events.toList()
        shakes.fire()
        runCurrent()

        assertEquals(2, repository.started)
        assertEquals(listOf(SleepTimerOutcome.Expired), repository.ended)
        assertEquals(eventsAtExpiry, history.events, "grace shake must not add audiobook History")
        assertTrue(controller.state.value.isActive)
    }

    @Test
    fun `shake grace expires and stops sensing without restarting anything`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                shakeToRestart = true,
                shakeGracePeriod = 10.seconds,
                fadeLength = Duration.ZERO,
            ),
        )
        val repository = FakeSleepTimerRepository(source)
        val shakes = FakeShakeSource()
        val clock = TestAppClock()
        val controller = controller(repository, shakes, clock)
        controller.attach(player())
        runCurrent()

        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(1.seconds)))
        clock.advanceBy(1.seconds)
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(shakes.isSensing)

        clock.advanceBy(10.seconds)
        advanceTimeBy(10_001)
        runCurrent()

        assertFalse(shakes.isSensing)
        assertEquals(1, repository.started)
        assertEquals(SleepTimerState.Idle, controller.state.value)
    }

    @Test
    fun `grace off stops sensing immediately when timer expires`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                shakeToRestart = true,
                shakeGracePeriod = Duration.ZERO,
                fadeLength = Duration.ZERO,
            ),
        )
        val shakes = FakeShakeSource()
        val clock = TestAppClock()
        val controller = controller(FakeSleepTimerRepository(source), shakes, clock)
        controller.attach(player())
        runCurrent()

        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(1.seconds)))
        clock.advanceBy(1.seconds)
        advanceTimeBy(1_001)
        runCurrent()

        assertFalse(shakes.isSensing)
        assertEquals(SleepTimerState.Idle, controller.state.value)
    }

    @Test
    fun `automatic expiry persistence cannot expose the expired session as restartable`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                defaultLength = 1.seconds,
                fadeLength = Duration.ZERO,
                shakeToRestart = true,
                shakeGracePeriod = 10.seconds,
                schedule = SleepTimerScheduleSettings.Default.copy(enabled = true),
            ),
        )
        val repository = FakeSleepTimerRepository(source)
        val shakes = FakeShakeSource()
        val clock = TestAppClock(Instant.parse("2026-09-19T23:00:00Z"))
        var resumes = 0
        val controller = controller(repository, shakes, clock)
        controller.attach(player(), resumeOwner { resumes += 1 })
        runCurrent()
        controller.onPlaybackChanged(isPlaying = true)
        runCurrent()
        assertEquals(1, repository.started)

        val persistence = repository.blockNextScheduleRuntimeWrite()
        clock.advanceBy(1.seconds)
        advanceTimeBy(1_001)
        runCurrent()
        persistence.awaitEntered()

        assertEquals(SleepTimerState.Idle, controller.state.value)
        assertTrue(shakes.isSensing)
        shakes.fire()
        runCurrent()

        assertEquals(2, repository.started)
        assertEquals(0, repository.restarted)
        assertTrue("timer-1" !in repository.restartedSessions)
        assertEquals(1, resumes)
        assertTrue(controller.state.value.isActive)

        persistence.resume()
        runCurrent()

        assertTrue(("timer-1" to SleepTimerOutcome.Expired) in repository.endedSessions)
        assertEquals(null, repository.replayRequiredOccurrence)
        assertEquals(2, repository.startedSessions.distinct().size)
    }

    @Test
    fun `book change invalidates a grace restart suspended in session creation`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                shakeToRestart = true,
                shakeGracePeriod = 10.seconds,
                fadeLength = Duration.ZERO,
            ),
        )
        val repository = FakeSleepTimerRepository(source)
        val shakes = FakeShakeSource()
        val clock = TestAppClock()
        var resumes = 0
        val controller = controller(repository, shakes, clock)
        controller.attach(player(), resumeOwner { resumes += 1 })
        runCurrent()
        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(1.seconds)))
        clock.advanceBy(1.seconds)
        advanceTimeBy(1_001)
        runCurrent()

        val creation = repository.blockNextRecordStarted()
        shakes.fire()
        creation.awaitEntered()
        controller.onBookChanged(emptyList())
        creation.resume()
        runCurrent()

        assertEquals(SleepTimerState.Idle, controller.state.value)
        assertFalse(shakes.isSensing)
        assertEquals(0, resumes)
        assertEquals(2, repository.started)
        assertTrue(("timer-2" to SleepTimerOutcome.PlaybackStopped) in repository.endedSessions)
        assertEquals(0, repository.restarted)
    }

    @Test
    fun `service recreation invalidates a claimed grace restart and cannot transfer it to the new player`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                shakeToRestart = true,
                shakeGracePeriod = 10.seconds,
                fadeLength = Duration.ZERO,
            ),
        )
        val repository = FakeSleepTimerRepository(source)
        val shakes = FakeShakeSource()
        val clock = TestAppClock()
        val blockedResume = BlockingResumeOwner()
        var oldRawPlay = 0
        var replacementRawPlay = 0
        var replacementResumes = 0
        val controller = controller(repository, shakes, clock)
        controller.attach(player(onPlay = { oldRawPlay += 1 }), blockedResume)
        runCurrent()
        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(1.seconds)))
        clock.advanceBy(1.seconds)
        advanceTimeBy(1_001)
        runCurrent()

        shakes.fire()
        blockedResume.gate.awaitEntered()
        assertEquals(1, blockedResume.calls)

        controller.attach(null)
        controller.attach(
            player(onPlay = { replacementRawPlay += 1 }),
            resumeOwner { replacementResumes += 1 },
        )
        blockedResume.gate.resume()
        runCurrent()

        assertEquals(SleepTimerState.Idle, controller.state.value)
        assertEquals(0, blockedResume.resumes)
        assertEquals(0, replacementResumes)
        assertEquals(0, oldRawPlay)
        assertEquals(0, replacementRawPlay)
        assertTrue(("timer-2" to SleepTimerOutcome.PlaybackStopped) in repository.endedSessions)
        assertFalse(shakes.isSensing)
    }

    @Test
    fun `rapid grace shakes claim one replacement session and one resume`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                shakeToRestart = true,
                shakeGracePeriod = 10.seconds,
                fadeLength = Duration.ZERO,
            ),
        )
        val repository = FakeSleepTimerRepository(source)
        val shakes = FakeShakeSource()
        val clock = TestAppClock()
        var resumes = 0
        val controller = controller(repository, shakes, clock)
        controller.attach(player(), resumeOwner { resumes += 1 })
        runCurrent()
        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(1.seconds)))
        clock.advanceBy(1.seconds)
        advanceTimeBy(1_001)
        runCurrent()

        val creation = repository.blockNextRecordStarted()
        shakes.fire()
        creation.awaitEntered()
        shakes.fireQueued()
        shakes.fireQueued()
        runCurrent()
        creation.resume()
        runCurrent()

        assertEquals(2, repository.started)
        assertEquals(1, resumes)
        assertEquals(0, repository.restarted)
        assertTrue(controller.state.value.isActive)
        assertEquals(3, shakes.startCalls, "active timer, grace, then replacement timer are the only registrations")
        assertEquals(2, shakes.stopCalls, "expiry and the exclusive grace claim are the only stops")
    }

    @Test
    fun `duplicate Pause intent after expiry revokes grace even while raw player is already paused`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                shakeToRestart = true,
                shakeGracePeriod = 10.seconds,
                fadeLength = Duration.ZERO,
            ),
        )
        val repository = FakeSleepTimerRepository(source)
        val shakes = FakeShakeSource()
        val clock = TestAppClock()
        var resumes = 0
        val controller = controller(repository, shakes, clock)
        controller.attach(player(), resumeOwner { resumes += 1 })
        runCurrent()
        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(1.seconds)))
        clock.advanceBy(1.seconds)
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(shakes.isSensing)

        controller.onControllerTransportIntent()
        runCurrent()
        shakes.fireQueued()
        runCurrent()

        assertFalse(shakes.isSensing)
        assertEquals(1, repository.started)
        assertEquals(0, resumes)
        assertEquals(SleepTimerState.Idle, controller.state.value)
    }

    @Test
    fun `controller Play intent supersedes grace before a queued shake can create a resume owner`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                shakeToRestart = true,
                shakeGracePeriod = 10.seconds,
                fadeLength = Duration.ZERO,
            ),
        )
        val repository = FakeSleepTimerRepository(source)
        val shakes = FakeShakeSource()
        val clock = TestAppClock()
        var graceResumes = 0
        val controller = controller(repository, shakes, clock)
        controller.attach(player(), resumeOwner { graceResumes += 1 })
        runCurrent()
        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(1.seconds)))
        clock.advanceBy(1.seconds)
        advanceTimeBy(1_001)
        runCurrent()

        controller.onControllerTransportIntent()
        runCurrent()
        shakes.fireQueued()
        runCurrent()

        assertEquals(1, repository.started)
        assertEquals(0, graceResumes)
        assertEquals(SleepTimerState.Idle, controller.state.value)
    }

    @Test
    fun `service owned raw resume intent revokes grace before Play`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                shakeToRestart = true,
                shakeGracePeriod = 10.seconds,
                fadeLength = Duration.ZERO,
            ),
        )
        val repository = FakeSleepTimerRepository(source)
        val shakes = FakeShakeSource()
        val clock = TestAppClock()
        var graceResumes = 0
        val controller = controller(repository, shakes, clock)
        controller.attach(player(), resumeOwner { graceResumes += 1 })
        runCurrent()
        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(1.seconds)))
        clock.advanceBy(1.seconds)
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(shakes.isSensing)

        controller.onServiceResumeIntent()
        shakes.fireQueued()
        runCurrent()

        assertFalse(shakes.isSensing)
        assertEquals(1, repository.started)
        assertEquals(0, graceResumes)
        assertEquals(SleepTimerState.Idle, controller.state.value)
    }

    @Test
    fun `Stop media replacement and listener seeks revoke grace authority`() = runTest {
        suspend fun attempt(origin: ResumeInvalidation): Pair<Int, Int> {
            val source = MutableStateFlow(
                SleepTimerSettings.Default.copy(
                    shakeToRestart = true,
                    shakeGracePeriod = 10.seconds,
                    fadeLength = Duration.ZERO,
                ),
            )
            val repository = FakeSleepTimerRepository(source)
            val shakes = FakeShakeSource()
            val clock = TestAppClock()
            var resumes = 0
            val controller = controller(repository, shakes, clock)
            controller.attach(player(), resumeOwner { resumes += 1 })
            runCurrent()
            assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(1.seconds)))
            clock.advanceBy(1.seconds)
            advanceTimeBy(1_001)
            runCurrent()
            assertTrue(shakes.isSensing)

            controller.onResumeInvalidation(origin)
            runCurrent()
            shakes.fireQueued()
            runCurrent()
            return repository.started to resumes
        }

        assertEquals(1 to 0, attempt(ResumeInvalidation.Stop))
        assertEquals(1 to 0, attempt(ResumeInvalidation.MediaChanged))
        assertEquals(1 to 0, attempt(ResumeInvalidation.Seek))
        assertEquals(1 to 0, attempt(ResumeInvalidation.NotificationSkip))
    }

    @Test
    fun `new controller Play invalidates a grace claim already suspended in freshness`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                shakeToRestart = true,
                shakeGracePeriod = 10.seconds,
                fadeLength = Duration.ZERO,
            ),
        )
        val repository = FakeSleepTimerRepository(source)
        val shakes = FakeShakeSource()
        val clock = TestAppClock()
        val blockedGraceResume = BlockingResumeOwner()
        val controller = controller(repository, shakes, clock)
        controller.attach(player(), blockedGraceResume)
        runCurrent()
        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(1.seconds)))
        clock.advanceBy(1.seconds)
        advanceTimeBy(1_001)
        runCurrent()

        shakes.fire()
        blockedGraceResume.gate.awaitEntered()
        assertEquals(1, blockedGraceResume.calls)

        controller.onControllerTransportIntent()
        blockedGraceResume.gate.resume()
        runCurrent()

        assertEquals(0, blockedGraceResume.resumes)
        assertEquals(SleepTimerState.Idle, controller.state.value)
        assertTrue(("timer-2" to SleepTimerOutcome.PlaybackStopped) in repository.endedSessions)
        assertFalse(shakes.isSensing)
    }

    @Test
    fun `claimed grace expires while freshness is suspended and cannot later resume`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                shakeToRestart = true,
                shakeGracePeriod = 10.seconds,
                fadeLength = Duration.ZERO,
            ),
        )
        val repository = FakeSleepTimerRepository(source)
        val shakes = FakeShakeSource()
        val clock = TestAppClock()
        val blockedResume = BlockingResumeOwner()
        val controller = controller(repository, shakes, clock)
        controller.attach(player(), blockedResume)
        runCurrent()
        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(1.seconds)))
        clock.advanceBy(1.seconds)
        advanceTimeBy(1_001)
        runCurrent()

        shakes.fire()
        blockedResume.gate.awaitEntered()
        clock.advanceBy(10.seconds)
        advanceTimeBy(10_001)
        runCurrent()
        blockedResume.gate.resume()
        runCurrent()

        assertEquals(0, blockedResume.resumes)
        assertEquals(SleepTimerState.Idle, controller.state.value)
        assertTrue(("timer-2" to SleepTimerOutcome.PlaybackStopped) in repository.endedSessions)
        assertFalse(shakes.isSensing)
    }

    @Test
    fun `grace deadline is half open before equal and after`() = runTest {
        suspend fun attempt(offset: Duration): Pair<Int, Int> {
            val source = MutableStateFlow(
                SleepTimerSettings.Default.copy(
                    shakeToRestart = true,
                    shakeGracePeriod = 10.seconds,
                    fadeLength = Duration.ZERO,
                ),
            )
            val repository = FakeSleepTimerRepository(source)
            val shakes = FakeShakeSource()
            val clock = TestAppClock()
            var resumes = 0
            val controller = controller(repository, shakes, clock)
            controller.attach(player(), resumeOwner { resumes += 1 })
            runCurrent()
            assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(1.seconds)))
            clock.advanceBy(1.seconds)
            advanceTimeBy(1_001)
            runCurrent()

            // Move only the injected monotonic clock. At equality the timeout coroutine has not been given a
            // chance to run, so rejection proves validation itself defines the boundary.
            clock.advanceBy(offset)
            shakes.fire()
            runCurrent()
            return repository.started to resumes
        }

        assertEquals(2 to 1, attempt(9_999.milliseconds))
        assertEquals(1 to 0, attempt(10.seconds))
        assertEquals(1 to 0, attempt(10_001.milliseconds))
    }

    @Test
    fun `automatic grace claimed before schedule end cannot commit after the occurrence ends`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                defaultLength = 1.seconds,
                fadeLength = Duration.ZERO,
                shakeToRestart = true,
                shakeGracePeriod = 10.seconds,
                schedule = SleepTimerScheduleSettings.Default.copy(
                    enabled = true,
                    start = java.time.LocalTime.of(22, 0),
                    end = java.time.LocalTime.of(7, 0),
                ),
            ),
        )
        val repository = FakeSleepTimerRepository(source)
        val shakes = FakeShakeSource()
        val clock = TestAppClock(Instant.parse("2026-09-20T06:59:55Z"))
        val blockedResume = BlockingResumeOwner()
        val controller = controller(repository, shakes, clock)
        controller.attach(player(), blockedResume)
        runCurrent()
        controller.onPlaybackChanged(isPlaying = true)
        runCurrent()
        assertEquals(1, repository.started)

        clock.advanceBy(1.seconds)
        advanceTimeBy(1_001)
        runCurrent()
        shakes.fire()
        blockedResume.gate.awaitEntered()

        // Cross 07:00 while still inside the ten-second monotonic grace window.
        clock.advanceBy(5.seconds)
        blockedResume.gate.resume()
        runCurrent()

        assertEquals(0, blockedResume.resumes)
        assertEquals(SleepTimerState.Idle, controller.state.value)
        assertTrue(("timer-2" to SleepTimerOutcome.PlaybackStopped) in repository.endedSessions)
    }

    @Test
    fun `automatic grace before schedule end restarts but a shake after end does not`() = runTest {
        suspend fun attempt(shakeAfterEnd: Boolean): Pair<Int, Int> {
            val source = MutableStateFlow(
                SleepTimerSettings.Default.copy(
                    defaultLength = 1.seconds,
                    fadeLength = Duration.ZERO,
                    shakeToRestart = true,
                    shakeGracePeriod = 10.seconds,
                    schedule = SleepTimerScheduleSettings.Default.copy(
                        enabled = true,
                        start = java.time.LocalTime.of(22, 0),
                        end = java.time.LocalTime.of(7, 0),
                    ),
                ),
            )
            val repository = FakeSleepTimerRepository(source)
            val shakes = FakeShakeSource()
            val clock = TestAppClock(Instant.parse("2026-09-20T06:59:55Z"))
            var resumes = 0
            val controller = controller(repository, shakes, clock)
            controller.attach(player(), resumeOwner { resumes += 1 })
            runCurrent()
            controller.onPlaybackChanged(isPlaying = true)
            runCurrent()
            clock.advanceBy(1.seconds)
            advanceTimeBy(1_001)
            runCurrent()
            if (shakeAfterEnd) clock.advanceBy(5.seconds)
            shakes.fire()
            runCurrent()
            return repository.started to resumes
        }

        assertEquals(2 to 1, attempt(shakeAfterEnd = false))
        assertEquals(1 to 0, attempt(shakeAfterEnd = true))
    }

    @Test
    fun `a start suspended in history creation cannot install on a replacement service player`() = runTest {
        val repository = FakeSleepTimerRepository(MutableStateFlow(SleepTimerSettings.Default))
        val shakes = FakeShakeSource()
        val controller = controller(repository, shakes, TestAppClock())
        controller.attach(player())
        runCurrent()
        val creation = repository.blockNextRecordStarted()
        var result: AppResult<Unit>? = null
        val startJob = launch {
            result = controller.start(SleepTimerMode.Fixed(30.minutes))
        }
        creation.awaitEntered()

        controller.attach(null)
        controller.attach(player(), resumeOwner())
        creation.resume()
        startJob.join()
        runCurrent()

        assertIs<AppResult.Failure>(result)
        assertEquals(SleepTimerState.Idle, controller.state.value)
        assertFalse(shakes.isSensing)
        assertTrue(("timer-1" to SleepTimerOutcome.PlaybackStopped) in repository.endedSessions)
    }

    @Test
    fun `end of chapter grace at the last boundary does not create an immediate-expiry replacement`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                shakeToRestart = true,
                shakeGracePeriod = 10.seconds,
                fadeLength = Duration.ZERO,
            ),
        )
        val repository = FakeSleepTimerRepository(source)
        val shakes = FakeShakeSource()
        val clock = TestAppClock()
        var position = 9.minutes
        var resumes = 0
        val controller = controller(repository, shakes, clock)
        controller.attach(
            player(position = { position }, onPlay = { resumes += 1 }),
            resumeOwner { resumes += 1 },
        )
        controller.onBookChanged(
            listOf(
                Chapter(
                    serverId = ServerId("chapter-1"),
                    bookId = LibraryItemId("book-a"),
                    index = 0,
                    title = "Chapter",
                    start = Duration.ZERO,
                    end = 10.minutes,
                ),
            ),
        )
        runCurrent()

        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.EndOfChapter))
        position = 10.minutes
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(shakes.isSensing)

        shakes.fire()
        runCurrent()

        assertEquals(1, repository.started)
        assertEquals(0, resumes)
        assertEquals(SleepTimerState.Idle, controller.state.value)
        assertFalse(shakes.isSensing)
    }

    @Test
    fun `changing shake sensitivity re-registers the active listener`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                shakeToRestart = true,
                shakeSensitivity = ShakeSensitivity.High,
            ),
        )
        val shakes = FakeShakeSource()
        val controller = controller(FakeSleepTimerRepository(source), shakes, TestAppClock())
        controller.attach(player())
        runCurrent()

        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(30.minutes)))
        assertEquals(ShakeSensitivity.High, shakes.sensitivity)
        assertEquals(1, shakes.startCalls)

        source.value = source.value.copy(shakeSensitivity = ShakeSensitivity.Low)
        runCurrent()

        assertEquals(ShakeSensitivity.Low, shakes.sensitivity)
        assertEquals(2, shakes.startCalls)
        assertEquals(1, shakes.stopCalls)
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
    fun `car connection suppresses new automatic timers but permits an explicit manual timer`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(schedule = SleepTimerScheduleSettings.Default.copy(enabled = true)),
        )
        val repository = FakeSleepTimerRepository(source)
        val controller = controller(repository, FakeShakeSource(), TestAppClock(Instant.parse("2026-09-19T23:00:00Z")))
        controller.attach(player())
        controller.onCarConnectionChanged(true)
        controller.onPlayRequest(explicit = true)
        controller.onPlaybackChanged(isPlaying = true)
        runCurrent()

        assertEquals(SleepTimerState.Idle, controller.state.value)
        assertEquals(0, repository.started)
        assertIs<AppResult.Success<Unit>>(controller.start(SleepTimerMode.Fixed(30.minutes)))
        assertTrue(controller.state.value.isActive)
        assertEquals(1, repository.started)
    }

    @Test
    fun `nightly boundary is suppressed in car and disconnect reconciles ongoing playback`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(schedule = SleepTimerScheduleSettings.Default.copy(enabled = true)),
        )
        val repository = FakeSleepTimerRepository(source)
        val clock = TestAppClock(Instant.parse("2026-09-19T21:59:00Z"))
        val controller = controller(repository, FakeShakeSource(), clock)
        controller.attach(player())
        controller.onCarConnectionChanged(true)
        controller.onPlaybackChanged(isPlaying = true)
        runCurrent()
        clock.advanceBy(1.minutes)
        advanceTimeBy(60_001)
        runCurrent()

        assertEquals(SleepTimerState.Idle, controller.state.value)
        assertEquals(0, repository.started)
        controller.onCarConnectionChanged(false)
        runCurrent()
        assertTrue(controller.state.value.isActive)
        assertEquals(1, repository.started)
    }

    @Test
    fun `car arrival preserves an already running automatic timer`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(schedule = SleepTimerScheduleSettings.Default.copy(enabled = true)),
        )
        val repository = FakeSleepTimerRepository(source)
        val controller = controller(repository, FakeShakeSource(), TestAppClock(Instant.parse("2026-09-19T23:00:00Z")))
        controller.attach(player())
        controller.onPlaybackChanged(isPlaying = true)
        runCurrent()
        val running = controller.state.value
        assertTrue(running.isActive)

        controller.onCarConnectionChanged(true)
        runCurrent()
        assertEquals(running, controller.state.value)
        assertEquals(1, repository.started)
        assertTrue(repository.ended.isEmpty())
    }

    @Test
    fun `disconnect does not rearm a manually cancelled nightly occurrence`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(schedule = SleepTimerScheduleSettings.Default.copy(enabled = true)),
        )
        val repository = FakeSleepTimerRepository(source)
        val controller = controller(repository, FakeShakeSource(), TestAppClock(Instant.parse("2026-09-19T23:00:00Z")))
        controller.attach(player())
        controller.onPlaybackChanged(isPlaying = true)
        runCurrent()
        controller.cancel()
        runCurrent()
        val suppressed = assertNotNull(repository.suppressedOccurrence)

        controller.onCarConnectionChanged(true)
        controller.onCarConnectionChanged(false)
        runCurrent()
        assertEquals(SleepTimerState.Idle, controller.state.value)
        assertEquals(suppressed, repository.suppressedOccurrence)
        assertEquals(1, repository.started)
    }

    @Test
    fun `car arrival invalidates an automatic start suspended in timer history persistence`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(schedule = SleepTimerScheduleSettings.Default.copy(enabled = true)),
        )
        val repository = FakeSleepTimerRepository(source)
        val gate = repository.blockNextRecordStarted()
        val controller = controller(repository, FakeShakeSource(), TestAppClock(Instant.parse("2026-09-19T23:00:00Z")))
        controller.attach(player())
        controller.onPlaybackChanged(isPlaying = true)
        gate.awaitEntered()
        controller.onCarConnectionChanged(true)
        runCurrent()
        gate.resume()
        runCurrent()

        assertEquals(SleepTimerState.Idle, controller.state.value)
        assertEquals(listOf("timer-1" to SleepTimerOutcome.PlaybackStopped), repository.endedSessions)
    }

    @Test
    fun `car arrival invalidates an automatic arm suspended in schedule persistence`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                schedule = SleepTimerScheduleSettings.Default.copy(
                    enabled = true,
                    suppressedOccurrence = "previous-night",
                ),
            ),
        )
        val repository = FakeSleepTimerRepository(source)
        val gate = repository.blockNextScheduleRuntimeWrite()
        val controller = controller(repository, FakeShakeSource(), TestAppClock(Instant.parse("2026-09-19T23:00:00Z")))
        controller.attach(player())
        controller.onPlaybackChanged(isPlaying = true)
        gate.awaitEntered()
        controller.onCarConnectionChanged(true)
        runCurrent()
        gate.resume()
        runCurrent()

        assertEquals(SleepTimerState.Idle, controller.state.value)
        assertEquals(0, repository.started)
    }

    @Test
    fun `disconnect while paused does not create a nightly timer`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(schedule = SleepTimerScheduleSettings.Default.copy(enabled = true)),
        )
        val repository = FakeSleepTimerRepository(source)
        val controller = controller(repository, FakeShakeSource(), TestAppClock(Instant.parse("2026-09-19T23:00:00Z")))
        controller.attach(player())
        controller.onCarConnectionChanged(true)
        controller.onCarConnectionChanged(false)
        runCurrent()

        assertEquals(SleepTimerState.Idle, controller.state.value)
        assertEquals(0, repository.started)
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
    fun `automatic timer keeps its schedule-end cancellation while playback is paused`() = runTest {
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
        assertTrue(controller.state.value.isActive)

        controller.onPlaybackChanged(isPlaying = false)
        runCurrent()

        clock.advanceBy(14.minutes)
        advanceTimeBy(14.minutes.inWholeMilliseconds + 1)
        runCurrent()

        assertEquals(SleepTimerState.Idle, controller.state.value)
        assertEquals(SleepTimerOutcome.Cancelled, repository.ended.last())
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
    fun `superseded Play cleanup cannot clear a newer explicit replay marker`() = runTest {
        val source = MutableStateFlow(
            SleepTimerSettings.Default.copy(
                defaultLength = 1.seconds,
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

        clock.advanceBy(1.seconds)
        advanceTimeBy(1_001)
        runCurrent()
        controller.onPlaybackChanged(isPlaying = false)
        runCurrent()
        assertNotNull(repository.replayRequiredOccurrence)
        assertEquals(1, repository.started)

        val oldRequest = controller.onPlayRequest(explicit = false)
        controller.onPlayRequest(explicit = true)
        controller.clearPlayRequest(oldRequest)
        controller.onPlaybackChanged(isPlaying = true)
        runCurrent()

        assertEquals(2, repository.started, "cleanup from the old Play must not erase newer explicit intent")
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
        history: PlaybackHistoryRepository = FakePlaybackHistoryRepository(),
        volumeEffects: PlaybackVolumeEffects = PlaybackVolumeEffects(clock),
        dispatcher: TestDispatcher = UnconfinedTestDispatcher(testScheduler),
    ): SleepTimerController {
        val sync = SessionSyncCoordinator(
            repository = FakeSessionSyncRepository(),
            baseline = ResumeBaseline(),
            clock = clock,
            logger = NO_OP_LOGGER,
            volumeEffects = volumeEffects,
            applicationScope = backgroundScope,
            mainDispatcher = dispatcher,
        )
        return SleepTimerController(
            repository = repository,
            shakes = shakes,
            volumeEffects = volumeEffects,
            history = SleepTimerHistory(repository, history, sync, clock, backgroundScope),
            clock = clock,
            zoneProvider = object : LocalZoneProvider {
                override fun current() = java.time.ZoneOffset.UTC
            },
            logger = NO_OP_LOGGER,
            applicationScope = backgroundScope,
            mainDispatcher = dispatcher,
        )
    }

    private fun routeTestPlayer(
        bookId: () -> String = { "book-a" },
        position: () -> Duration = { 10.minutes },
        onVolumeChanged: (Float) -> Unit,
    ): ExoPlayer {
        var volume = 1f
        return Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getVolume" -> volume

                "setVolume" -> {
                    volume = args!![0] as Float
                    onVolumeChanged(volume)
                    null
                }

                "isPlaying" -> true

                "getCurrentMediaItem" -> MediaItem.Builder().setMediaId(bookId()).build()

                "getCurrentPosition" -> position().inWholeMilliseconds

                "getDuration" -> 60.minutes.inWholeMilliseconds

                "addListener", "removeListener" -> null

                else -> defaultValue(method.returnType)
            }
        } as ExoPlayer
    }

    private fun TestScope.routeTestGuard(
        player: ExoPlayer,
        volumeEffects: PlaybackVolumeEffects,
        dispatcher: TestDispatcher,
    ) = HeadsetRouteGuard(
        scope = backgroundScope,
        playerDispatcher = dispatcher,
        volumeEffects = volumeEffects,
        isCurrentProbe = { _, current, _ -> current === player },
        onProbeInvalidated = { false },
        onProbeFinished = {},
        onPlaying = {},
    )

    private fun resumeOwner(onResume: () -> Unit = {}): SleepTimerResumeOwner = object : SleepTimerResumeOwner {
        override suspend fun resume(stillAuthorized: () -> Boolean): Boolean {
            if (!stillAuthorized()) return false
            onResume()
            return true
        }
    }

    private class BlockingResumeOwner : SleepTimerResumeOwner {
        val gate = SuspendGate()
        var calls = 0
            private set
        var resumes = 0
            private set

        override suspend fun resume(stillAuthorized: () -> Boolean): Boolean {
            calls += 1
            gate.block()
            if (!stillAuthorized()) return false
            resumes += 1
            return true
        }
    }

    private fun player(
        onPlay: (() -> Unit)? = null,
        position: () -> Duration = { 10.minutes },
        playing: () -> Boolean = { true },
        hasBook: Boolean = true,
    ): Player {
        val item = MediaItem.Builder().setMediaId("book-a").build()
        return Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getCurrentMediaItem" -> item.takeIf { hasBook }
                "getCurrentPosition" -> position().inWholeMilliseconds
                "getDuration" -> 60.minutes.inWholeMilliseconds
                "getVolume" -> 1f
                "isPlaying" -> playing()
                "play" -> invokePlay(onPlay)
                else -> defaultValue(method.returnType)
            }
        } as Player
    }

    private fun invokePlay(onPlay: (() -> Unit)?): Any? {
        onPlay?.invoke()
        return null
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
        var sensitivity: ShakeSensitivity? = null
            private set
        private var callback: (() -> Unit)? = null
        private var lastCallback: (() -> Unit)? = null

        override fun start(sensitivity: ShakeSensitivity, onShake: () -> Unit): Boolean {
            startCalls += 1
            this.sensitivity = sensitivity
            callback = onShake
            lastCallback = onShake
            isSensing = true
            return true
        }

        override fun stop() {
            if (isSensing) stopCalls += 1
            callback = null
            sensitivity = null
            isSensing = false
        }

        fun fire() {
            check(isSensing)
            callback?.invoke()
        }

        /** Models a sensor callback already queued when the first shake stopped registration. */
        fun fireQueued() {
            lastCallback?.invoke()
        }
    }

    private class SuspendGate {
        val entered = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()

        suspend fun block() {
            entered.complete(Unit)
            released.await()
        }

        suspend fun awaitEntered() {
            entered.await()
        }

        fun resume() {
            released.complete(Unit)
        }
    }

    private class FakeSleepTimerRepository(
        private val settings: Flow<SleepTimerSettings> = flowOf(SleepTimerSettings.Default),
    ) : SleepTimerRepository {
        var started = 0
            private set
        var restarted = 0
            private set
        val startedSessions = mutableListOf<String>()
        val restartedSessions = mutableListOf<String>()
        val endedSessions = mutableListOf<Pair<String, SleepTimerOutcome>>()
        val ended = mutableListOf<SleepTimerOutcome>()
        private var nextStartedGate: SuspendGate? = null
        private var nextScheduleGate: SuspendGate? = null
        var suppressedOccurrence: String? = null
            private set
        var replayRequiredOccurrence: String? = null
            private set

        override fun observeSettings(): Flow<SleepTimerSettings> = settings

        fun blockNextRecordStarted(): SuspendGate = SuspendGate().also { nextStartedGate = it }

        fun blockNextScheduleRuntimeWrite(): SuspendGate = SuspendGate().also { nextScheduleGate = it }

        override suspend fun setDefaultLength(length: Duration): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun setFadeLength(length: Duration): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun setShakeToRestart(enabled: Boolean): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun setShakeGracePeriod(length: Duration): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun setShakeSensitivity(sensitivity: ShakeSensitivity): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun setRewindOnStop(length: Duration): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun setScheduleEnabled(enabled: Boolean): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun setScheduleWindow(start: java.time.LocalTime, end: java.time.LocalTime): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun setScheduleRuntimeState(
            suppressedOccurrence: String?,
            replayRequiredOccurrence: String?,
        ): AppResult<Unit> {
            nextScheduleGate?.also { nextScheduleGate = null }?.block()
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
            val sessionId = "timer-$started"
            startedSessions += sessionId
            nextStartedGate?.also { nextStartedGate = null }?.block()
            return AppResult.Success(sessionId)
        }

        override suspend fun recordRestarted(sessionId: String): AppResult<Unit> {
            restarted += 1
            restartedSessions += sessionId
            return AppResult.Success(Unit)
        }

        override suspend fun recordEnded(sessionId: String, outcome: SleepTimerOutcome): AppResult<Unit> {
            ended += outcome
            endedSessions += sessionId to outcome
            return AppResult.Success(Unit)
        }

        override suspend fun closeOrphanedSessions(): AppResult<Int> = AppResult.Success(0)
    }

    private class FakePlaybackHistoryRepository : PlaybackHistoryRepository {
        val events = mutableListOf<PlaybackEvent>()
        val snapshots = mutableListOf<HistorySnapshot>()

        override fun observe(bookId: LibraryItemId, limit: Int): Flow<List<PlaybackHistoryEntry>> = flowOf(emptyList())

        override suspend fun record(
            bookId: LibraryItemId,
            event: PlaybackEvent,
            from: Duration?,
            to: Duration,
            detail: Duration?,
            at: Instant?,
            owner: ProfileId?,
        ) {
            events += event
            snapshots += HistorySnapshot(bookId, to, at, owner)
        }

        override suspend fun refreshServerSessions(bookId: LibraryItemId) = Unit

        override suspend fun clear(bookId: LibraryItemId) = Unit
    }

    private data class HistorySnapshot(
        val bookId: LibraryItemId,
        val position: Duration,
        val at: Instant?,
        val owner: ProfileId?,
    )

    private class FakeSessionSyncRepository : SessionSyncRepository {
        override suspend fun openSession(
            session: com.example.shelfplayer.core.model.library.PlaybackSession,
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
