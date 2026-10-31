package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.playback.AudioOutput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** PLAY-002 / PLAY-008: real recovery, gate, router and player effects, including their coroutine lifetime. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarContinuityResumeTransactionTest {
    @Test
    fun `accepted claim issues one silent Play and one timer intent until exact track proof`() = runTest {
        val fixture = Fixture(this)
        fixture.effects.recoverAndResume(fixture.target)
        fixture.effects.recoverAndResume(fixture.target)

        assertEquals(1, fixture.current.plays)
        assertEquals(1, fixture.timerResumes)
        assertEquals(listOf("volume:0.0", "play"), fixture.current.operations)
        assertTrue(fixture.recovery.isPlayAwaiting)

        fixture.current.playing = true
        fixture.actualRoutes = setOf(fixture.headset.id)
        fixture.router.onActualTrackRouteChanged()
        runCurrent()
        assertEquals(1f, fixture.current.volume)
        assertFalse(fixture.current.operations.contains("pause"))
    }

    @Test
    fun `wrong multiple and absent measured routes pause before restoring volume`() = runTest {
        for (routes in listOf(null, emptySet(), setOf("other"), setOf("other", "original"))) {
            val fixture = Fixture(this)
            fixture.effects.recoverAndResume(fixture.target)
            fixture.current.playing = true
            fixture.actualRoutes = routes
            fixture.router.onActualTrackRouteChanged()
            runCurrent()
            if (routes.isNullOrEmpty()) {
                assertEquals(0f, fixture.current.volume, "missing measurement stays silent")
                advanceTimeBy(5_000)
                runCurrent()
            }
            val operations = fixture.current.operations
            assertTrue(operations.indexOf("pause") in 0 until operations.indexOf("volume:1.0"))
            assertEquals(1, fixture.current.plays)
            assertEquals(1f, fixture.current.volume)
            assertFalse(fixture.current.playing)
        }
    }

    @Test
    fun `same-target reassertion cannot unmute a stale probe on an unknown track route`() = runTest {
        val fixture = Fixture(this)
        fixture.effects.recoverAndResume(fixture.target)
        fixture.current.playing = true
        fixture.actualRoutes = null

        val reassertion = async { fixture.router.reassert(fixture.headset.id) }
        runCurrent()
        assertTrue(reassertion.await())
        runCurrent()

        assertEquals(1, fixture.current.plays)
        assertTrue(
            fixture.current.operations.indexOf("pause") in 0 until fixture.current.operations.indexOf("volume:1.0"),
        )
        assertEquals(1f, fixture.current.volume)
        assertFalse(fixture.current.playing)
    }

    @Test
    fun `car choice during stale probe measurement prevents old pause and leaves cleanup to new intent`() = runTest {
        val fixture = Fixture(this)
        fixture.effects.recoverAndResume(fixture.target)
        fixture.current.playing = true
        fixture.router.field<RoutePreferenceEffects>("preferenceEffects").supersede()
        var routeReads = 0
        var measuredDuringInvalidation = false
        fixture.router.setField("currentTrackRoutes") {
            routeReads++
            // Publication reads once, then the queued safety check reads the track and the
            // retired probe rechecks it. Invalidate only that third observation; selectCar
            // republishes synchronously and must not recursively trigger this hook again.
            if (routeReads == 3) {
                measuredDuringInvalidation = true
                fixture.router.selectCar()
            }
            null
        }

        fixture.router.onActualTrackRouteChanged()
        runCurrent()

        assertTrue(measuredDuringInvalidation, "the stale-probe route must be measured before intent changes")
        assertFalse(fixture.current.operations.contains("pause"), "the superseded callback must not pause")
        assertEquals(0f, fixture.current.volume, "the newer Car choice still needs its own output proof")
        fixture.router.select(null)
        runCurrent()
        assertEquals(1f, fixture.current.volume, "explicit release retires the remaining mute claim")
    }

    @Test
    fun `refused muted probe cannot issue Play or resume the timer`() = runTest {
        val fixture = Fixture(this)
        fixture.volumeEffects.detach(fixture.current.player)
        fixture.router.setField("player", null)
        fixture.effects.recoverAndResume(fixture.target)

        assertEquals(0, fixture.current.plays)
        assertEquals(0, fixture.timerResumes)
        assertTrue(fixture.current.operations.isEmpty())
        assertFalse(fixture.recovery.isPlayAwaiting)
        assertFalse(fixture.recovery.isCurrent(fixture.target, fixture.owner.currentGeneration, 0))
    }

    @Test
    fun `suspended recovery uses the current player after route availability returns`() = runTest {
        val fixture = Fixture(this, available = false)
        val oldPlayer = fixture.current
        val pending = launch { fixture.effects.recoverAndResume(fixture.target) }
        runCurrent()
        assertFalse(pending.isCompleted)

        fixture.current = RecordingPlayer()
        fixture.volumeEffects.attach(fixture.current.player)
        fixture.router.setField("player", fixture.current.player)
        fixture.returnHeadset()
        runCurrent()
        pending.join()

        assertEquals(0, oldPlayer.plays)
        assertTrue(oldPlayer.operations.isEmpty())
        assertEquals(1, fixture.current.plays)
        assertEquals(1, fixture.timerResumes)
    }

    @Test
    fun `cancelling a suspended transaction releases only its own claim`() = runTest {
        val fixture = Fixture(this, available = false)
        val pending = launch { fixture.effects.recoverAndResume(fixture.target) }
        runCurrent()
        pending.cancelAndJoin()
        fixture.returnHeadset()
        runCurrent()

        assertEquals(0, fixture.current.plays)
        assertEquals(0, fixture.timerResumes)
        assertFalse(fixture.recovery.isCurrent(fixture.target, fixture.owner.currentGeneration, 0))
    }

    @Test
    fun `older timeout leaves newer suspended departure eligible for its one Play`() = runTest {
        val fixture = Fixture(this, available = false)
        val old = launch { fixture.effects.recoverAndResume(fixture.target) }
        runCurrent()
        advanceTimeBy(100)
        val newer = fixture.nextDeparture()
        val next = launch { fixture.effects.recoverAndResume(newer) }
        runCurrent()

        advanceTimeBy(150)
        runCurrent()
        old.join()
        assertTrue(fixture.recovery.isCurrent(newer, fixture.owner.currentGeneration, 0))
        assertEquals(0, fixture.current.plays)

        fixture.returnHeadset()
        runCurrent()
        next.join()
        assertEquals(1, fixture.current.plays)
        assertEquals(1, fixture.timerResumes)
    }

    @Test
    fun `scheduled duplicate Ready callbacks and later controller binds keep one transaction`() = runTest {
        val fixture = Fixture(this, available = false)
        fixture.effects.onArrival(fixture.decision())
        runCurrent()
        fixture.effects.onRecoveryReady(fixture.target)
        fixture.effects.onArrival(null)
        runCurrent()
        assertTrue(fixture.recovery.isCurrent(fixture.target, fixture.owner.currentGeneration, 0))

        fixture.returnHeadset()
        runCurrent()
        assertEquals(1, fixture.current.plays)
        assertEquals(1, fixture.timerResumes)
    }

    @Test
    fun `new scheduled episode cancels old wait and accepts only its own Ready claim`() = runTest {
        val fixture = Fixture(this, available = false)
        fixture.effects.onRecoveryReady(fixture.target)
        runCurrent()
        val newer = fixture.nextDeparture()
        fixture.effects.onDeparture(fixture.decision(newer))
        runCurrent()
        assertTrue(fixture.recovery.isCurrent(newer, fixture.owner.currentGeneration, 0))

        fixture.returnHeadset()
        runCurrent()
        assertEquals(1, fixture.current.plays)
        assertEquals(1, fixture.timerResumes)
        assertEquals(newer, fixture.recovery.playbackBecameActive(fixture.owner.currentGeneration, 0))
    }

    @Test
    fun `explicit cancellation and service scope disposal stop scheduled recovery`() = runTest {
        for (cancelScope in listOf(false, true)) {
            val fixture = Fixture(this, available = false)
            fixture.effects.onRecoveryReady(fixture.target)
            runCurrent()
            if (cancelScope) {
                fixture.workScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            } else {
                fixture.effects.cancelPending()
            }
            fixture.returnHeadset()
            runCurrent()
            assertEquals(0, fixture.current.plays)
            assertEquals(0, fixture.timerResumes)
            assertFalse(fixture.recovery.isCurrent(fixture.target, fixture.owner.currentGeneration, 0))
        }
    }

    @Test
    fun `new explicit selection discovered during hold synchronization prevents any old reassertion`() = runTest {
        val fixture = Fixture(this)
        fixture.synchronizeIntent = {
            fixture.router.selectCar()
            fixture.effects.cancelPending()
        }
        fixture.effects.onArrival(null)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        assertEquals(null, fixture.router.selectedId.value)
        assertEquals(null, fixture.router.field<String?>("guardedHeadsetId"))
        assertEquals(listOf<Any?>(null), fixture.current.preferredRequests, "the new Car request must reach Media3")
        assertEquals(0, fixture.current.plays)
        assertEquals(0, fixture.timerResumes)
    }

    @Test
    fun `synchronous Media3 Play callback cancels its own work without losing audible confirmation`() = runTest {
        val fixture = Fixture(this, immediate = true)
        fixture.current.onPlay = fixture.effects::cancelPending
        fixture.effects.onRecoveryReady(fixture.target)

        assertEquals(1, fixture.current.plays)
        assertEquals(1, fixture.timerResumes)
        assertTrue(fixture.recovery.isPlayAwaiting)
        assertEquals(fixture.target, fixture.recovery.playbackBecameActive(fixture.owner.currentGeneration, 0))
    }

    @Test
    fun `later car bind protects suppressed Media3 intent without reassertion or extra Play`() = runTest {
        val fixture = Fixture(this)
        fixture.current.playWhenReady = true
        fixture.router.setField("guardedHeadsetId", null)
        fixture.effects.onArrival(null)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        assertEquals(fixture.headset.id, fixture.router.field<String?>("guardedHeadsetId"))
        assertTrue(fixture.current.preferredRequests.isEmpty(), "Media3 owns restoration of its active intent")
        assertEquals(0, fixture.current.plays)
        assertEquals(0, fixture.timerResumes)
    }

    internal class Fixture(scope: TestScope, available: Boolean = true, immediate: Boolean = false) {
        private val dispatcher = if (immediate) {
            UnconfinedTestDispatcher(
                scope.testScheduler,
            )
        } else {
            StandardTestDispatcher(scope.testScheduler)
        }
        val volumeEffects = volumeEffectsForTest(dispatcher)
        private val registry = LiveOutputEndpointRegistry()
        private val device = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build()
        private val endpoint = registry.reconcile(
            listOf(LiveOutputEndpointRegistry.Observation(device.id, device.type, device.productName?.toString())),
        ).single()
        val headset = endpoint.output.copy(id = endpoint.id, isActive = true)
        val owner = RouteHeardOwnership(OutputIntentOwner()).apply {
            onBookChanged(hasBook = true)
            onPlaybackObserved(listOf(headset))
        }
        val recovery =
            CarContinuityRecoveryCoordinator(CarArrivalResumeGate(), CarArrivalRouteRecovery(250.milliseconds))
        val target: CarArrivalResumeGate.Target
        var current = RecordingPlayer()
        var actualRoutes: Set<String>? = null
        var timerResumes = 0
        var synchronizeIntent: () -> Unit = {}
        val workScope = kotlinx.coroutines.CoroutineScope(
            scope.backgroundScope.coroutineContext +
                kotlinx.coroutines.SupervisorJob(scope.backgroundScope.coroutineContext[kotlinx.coroutines.Job]) +
                if (immediate) {
                    UnconfinedTestDispatcher(
                        scope.testScheduler,
                    )
                } else {
                    StandardTestDispatcher(scope.testScheduler)
                },
        )
        private val logger = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
        val router = AudioOutputRouter(
            RuntimeEnvironment.getApplication(),
            logger,
            scope.backgroundScope,
            dispatcher,
            registry,
            volumeEffects = volumeEffects,
        )
        val available: MutableStateFlow<List<AudioOutput>> = router.field("_outputs")
        val effects: CarContinuityResumeEffects

        init {
            router.positions.bind(routingOnlyPositionEffects())
            volumeEffects.attach(current.player)
            router.setField("player", current.player)
            router.setField("live", if (available) mapOf(headset.id to device) else emptyMap())
            router.setField("guardedHeadsetId", headset.id)
            router.setField("currentTrackRoutes", { actualRoutes })
            router.field<MutableStateFlow<String?>>("_selectedId").value = headset.id
            this.available.value = if (available) listOf(headset) else emptyList()
            recovery.observePlayingHeadset(inputs())
            recovery.platformInterruption(10.seconds, inputs(), focusLoss = true)
            target = requireNotNull(recovery.carArrival(11.seconds, owner.currentGeneration, 0).target)
            effects = CarContinuityResumeEffects(
                recovery, router, owner, { _, _ -> }, { current.player }, { 0 }, { emptyList() },
                resumePlayer = { current, authorized, beforeRawPlay ->
                    if (!authorized()) {
                        false
                    } else {
                        timerResumes++
                        beforeRawPlay()
                        current.play()
                        true
                    }
                },
                scope = workScope,
                synchronizeOutputIntent = { synchronizeIntent() },
                onOutputsChanged = {},
            )
        }

        fun returnHeadset() {
            router.setField("live", mapOf(headset.id to device))
            available.value = listOf(headset)
        }

        fun nextDeparture(): CarArrivalResumeGate.Target {
            recovery.carDeparture(12.seconds, owner.currentGeneration, 0, physicalProjection = true)
            return requireNotNull(recovery.platformInterruption(13.seconds, inputs(), focusLoss = true).target)
        }

        fun decision(target: CarArrivalResumeGate.Target = this.target) = CarArrivalResumeGate.Decision(
            target.phase,
            CarArrivalResumeGate.Status.Ready,
            CarArrivalResumeGate.Reason.BoundaryMatchedFocusLoss,
            target,
        )

        private fun inputs() = CarContinuityRecoveryCoordinator.Inputs(
            owner.heardRoute,
            headset.id,
            owner.currentGeneration,
            0,
            carConnected = false,
        )
    }

    internal class RecordingPlayer {
        var playing = false
        var playWhenReady = false
        var volume = 1f
        var plays = 0
        var onPlay: () -> Unit = {}
        val operations = mutableListOf<String>()
        val preferredRequests = mutableListOf<Any?>()
        private val thread = Thread.currentThread()
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) {
                _,
                method,
                args,
            ->
            check(Thread.currentThread() == thread) { "Media3 call off its application dispatcher" }
            when (method.name) {
                "addListener", "removeListener" -> null

                "getPlaybackState" -> androidx.media3.common.Player.STATE_READY

                "getPlayWhenReady" -> playWhenReady

                "getMediaItemCount" -> 1

                "getCurrentMediaItem" -> androidx.media3.common.MediaItem.EMPTY

                "isPlaying" -> playing

                "getVolume" -> volume

                "setVolume" -> {
                    volume = args!![0] as Float
                    operations += "volume:$volume"
                    null
                }

                "play" -> {
                    plays++
                    playWhenReady = true
                    operations += "play"
                    onPlay()
                    null
                }

                "pause" -> {
                    playing = false
                    playWhenReady = false
                    operations += "pause"
                    null
                }

                "setPreferredAudioDevice" -> {
                    preferredRequests += args?.get(0)
                    null
                }

                else -> error("Unexpected player effect: ${method.name}")
            }
        } as ExoPlayer
    }

    private companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T> AudioOutputRouter.field(name: String): T {
            if (name == "guardedHeadsetId") {
                val owner = AudioOutputRouter::class.java.getDeclaredField("headsetGuard").run {
                    isAccessible = true
                    get(this@field)
                }
                return HeadsetRouteGuard::class.java.getDeclaredField("endpointId").run {
                    isAccessible = true
                    get(owner) as T
                }
            }
            return AudioOutputRouter::class.java.getDeclaredField(name).run {
                isAccessible = true
                get(this@field) as T
            }
        }

        fun AudioOutputRouter.setField(name: String, value: Any?) {
            if (name == "player") {
                val guard = AudioOutputRouter::class.java.getDeclaredField("headsetGuard").run {
                    isAccessible = true
                    get(this@setField) as HeadsetRouteGuard
                }
                guard.detachPlayer()
                if (value is ExoPlayer) guard.attachPlayer(value)
            }
            if (name == "guardedHeadsetId") {
                val owner = AudioOutputRouter::class.java.getDeclaredField("headsetGuard").run {
                    isAccessible = true
                    get(this@setField) as HeadsetRouteGuard
                }
                if (value == null) owner.release() else owner.protect(value as String)
                return
            }
            val target: Any = if (name == "live") localOutputs else this
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        }
    }
}
