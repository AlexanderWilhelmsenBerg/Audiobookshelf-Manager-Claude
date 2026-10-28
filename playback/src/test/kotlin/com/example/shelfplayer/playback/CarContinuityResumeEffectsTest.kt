package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests the production recovery-to-Play boundary, without creating another player or recovery policy.
 * Media3's already-active play intent must remain the service's sole playback authority.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class CarContinuityResumeEffectsTest {
    @Test
    fun `car rebind cannot restore the former headset after explicit Car selection`() = runTest {
        val registry = LiveOutputEndpointRegistry()
        val device = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build()
        val endpoint = registry.reconcile(
            listOf(LiveOutputEndpointRegistry.Observation(device.id, device.type, device.productName?.toString())),
        ).single()
        val logger = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
        val router = AudioOutputRouter(
            RuntimeEnvironment.getApplication(),
            logger,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            registry,
            volumeEffects = volumeEffectsForTest(StandardTestDispatcher(testScheduler)),
        )
        AudioOutputRouter::class.java.getDeclaredField("live").apply { isAccessible = true }
            .set(router, mapOf(endpoint.id to device))
        AudioOutputRouter::class.java.getDeclaredField("currentTrackRoutes").apply { isAccessible = true }
            .set(router, { setOf(endpoint.id) })
        val owner = RouteHeardOwnership(router.outputIntentOwner).apply { onBookChanged(true) }
        router.selectCar()
        owner.onPlaybackObserved(router.outputs.value)
        val player = activePlayer()
        val effects = CarContinuityResumeEffects(
            recovery = CarContinuityRecoveryCoordinator(CarArrivalResumeGate()),
            outputs = router,
            owner = owner,
            trace = { _, _ -> },
            currentPlayer = { player },
            selectionSequence = { requireNotNull(router.explicitSelection.value).sequence },
            snapshot = { emptyList() },
            onServiceResumeIntent = { error("Rebind must not create Play intent") },
            scope = backgroundScope,
            synchronizeOutputIntent = {
                val selection = requireNotNull(router.explicitSelection.value)
                owner.onExplicitSelection(
                    selection.outputId,
                    router.outputs.value,
                    true,
                    selection.sequence,
                    selection.destination,
                )
            },
        )

        effects.onArrival(null)
        runCurrent()

        assertEquals(OutputIntentOwner.Destination.Car, router.explicitSelection.value?.destination)
        assertNull(router.selectedId.value, "car binding must not re-protect the former headset")
        assertNull(owner.headsetForCar(router.outputs.value))
        effects.cancelPending()
        router.detach()
    }

    @Test
    fun `active Media3 intent never secures a route or issues another Play`() = runTest {
        val events = mutableListOf<LogEvent>()
        val logger = object : Logger {
            override fun log(event: LogEvent) {
                events += event
            }
        }
        val player = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPlayWhenReady" -> true
                else -> error("An active intent must not call Media3 ${method.name}")
            }
        } as ExoPlayer
        val coordinator = CarContinuityRecoveryCoordinator(CarArrivalResumeGate())
        val recorder = AndroidAutoTraceRecorder(logger, TestAppClock()) { emptyList() }
        val effects = CarContinuityResumeEffects(
            recovery = coordinator,
            outputs = AudioOutputRouter(
                context = RuntimeEnvironment.getApplication(),
                logger = logger,
                applicationScope = this,
                mainDispatcher = StandardTestDispatcher(testScheduler),
                volumeEffects = volumeEffectsForTest(StandardTestDispatcher(testScheduler)),
            ),
            owner = RouteHeardOwnership(OutputIntentOwner()),
            trace = { message, fields -> recorder.log(message, fields = fields) },
            currentPlayer = { player },
            selectionSequence = { 0 },
            snapshot = { emptyList() },
            onServiceResumeIntent = { error("Active playback must not ask the sleep timer to resume") },
            scope = this,
        )
        effects.recoverAndResume(
            CarArrivalResumeGate.Target(
                outputId = "bluetooth:fixture",
                generation = 1,
                explicitSelectionSequence = 0,
                phase = CarArrivalResumeGate.Phase.Arrival,
            ),
        )
        assertTrue(events.any { it.message == "Car lifecycle left active playback intent with Media3" })
        assertTrue(!coordinator.isPlayAwaiting)
    }

    @Test
    fun `held-headset logging exposes only kind and phase, never endpoint identifier`() = runTest {
        val events = mutableListOf<LogEvent>()
        val logger = object : Logger {
            override fun log(event: LogEvent) {
                events += event
            }
        }
        val recorder = AndroidAutoTraceRecorder(logger, TestAppClock()) { emptyList() }
        val effects = CarContinuityResumeEffects(
            recovery = CarContinuityRecoveryCoordinator(CarArrivalResumeGate()),
            outputs = AudioOutputRouter(
                context = RuntimeEnvironment.getApplication(),
                logger = logger,
                applicationScope = this,
                mainDispatcher = StandardTestDispatcher(testScheduler),
                volumeEffects = volumeEffectsForTest(StandardTestDispatcher(testScheduler)),
            ),
            owner = RouteHeardOwnership(OutputIntentOwner()),
            trace = { message, fields -> recorder.log(message, fields = fields) },
            currentPlayer = { null },
            selectionSequence = { 0 },
            snapshot = { emptyList() },
            onServiceResumeIntent = {},
            scope = this,
        )
        effects.logHeldHeadset("bluetooth:private-fixture-id")
        val values = events.last().fields.filterIsInstance<LogField.Public>().associate { it.key to it.value }
        assertEquals("bluetooth", values["kind"])
        assertEquals("route-hold", values["phase"])
        assertTrue(values.values.none { it.contains("private-fixture-id") })
    }

    @Test
    fun `suspended route recovery refuses a replaced book or newer destination before Play`() = runTest {
        // The same production gate and effect entrypoint are used in both permutations.
        // An unavailable endpoint keeps secure() suspended; virtual time advances the bounded wait.
        for (replaceBook in listOf(true, false)) {
            val headset = AudioOutput(
                id = "bluetooth:held",
                displayName = "Fixture",
                kind = DeviceKind.Bluetooth,
                role = AudioOutputRole.Ambiguous,
                isActive = true,
            )
            val owner = RouteHeardOwnership(OutputIntentOwner()).apply {
                onBookChanged(hasBook = true)
                onPlaybackObserved(listOf(headset))
            }
            val gate = CarArrivalResumeGate()
            val recovery = CarContinuityRecoveryCoordinator(gate, CarArrivalRouteRecovery(250.milliseconds))
            val target = readyTarget(gate, owner, headset.id)

            var selection = 0L
            val events = mutableListOf<LogEvent>()
            val logger = object : Logger {
                override fun log(event: LogEvent) {
                    events += event
                }
            }
            val recorder = AndroidAutoTraceRecorder(logger, TestAppClock()) { emptyList() }
            val player = pausedPlayer()
            val effects = CarContinuityResumeEffects(
                recovery = recovery,
                outputs = AudioOutputRouter(
                    context = RuntimeEnvironment.getApplication(),
                    logger = logger,
                    applicationScope = this,
                    mainDispatcher = StandardTestDispatcher(testScheduler),
                    volumeEffects = volumeEffectsForTest(StandardTestDispatcher(testScheduler)),
                ),
                owner = owner,
                trace = { message, fields -> recorder.log(message, fields = fields) },
                currentPlayer = { player },
                selectionSequence = { selection },
                snapshot = { emptyList() },
                onServiceResumeIntent = { error("Stale recovery must not resume the timer") },
                scope = this,
            )

            val pending = launch { effects.recoverAndResume(target) }
            runCurrent()
            if (replaceBook) owner.onBookChanged(hasBook = true) else selection++
            advanceUntilIdle()
            pending.join()

            val expectedReason = if (replaceBook) "generation-changed" else "explicit-selection-changed"
            assertTrue(
                events.any { event ->
                    event.message == "Car lifecycle continuity did not secure the headset" &&
                        event.fields.filterIsInstance<LogField.Public>()
                            .any { field -> field.key == "reason" && field.value == expectedReason }
                },
                "Recovery must report why the old claim became invalid",
            )
            assertFalse(events.any { it.message == "Car lifecycle continuity issued Play" })
            assertFalse(recovery.isPlayAwaiting)
        }
    }

    private fun readyTarget(
        gate: CarArrivalResumeGate,
        owner: RouteHeardOwnership,
        headsetId: String,
    ): CarArrivalResumeGate.Target {
        gate.observePlayingHeadset(owner.heardRoute, headsetId, owner.currentGeneration, 0)
        assertEquals(
            CarArrivalResumeGate.Status.Armed,
            gate.onAudioFocusLoss(10.seconds, owner.heardRoute, headsetId, owner.currentGeneration, 0, false).status,
        )
        val arrival = gate.onCarArrival(11.seconds, owner.currentGeneration, 0)
        assertEquals(CarArrivalResumeGate.Status.Ready, arrival.status)
        return requireNotNull(arrival.target)
    }

    private fun pausedPlayer(): ExoPlayer = Proxy.newProxyInstance(
        ExoPlayer::class.java.classLoader,
        arrayOf(ExoPlayer::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getPlayWhenReady" -> false
            "getMediaItemCount" -> 1
            else -> error("Stale recovery must not call Media3 ${method.name}")
        }
    } as ExoPlayer

    private fun activePlayer(): ExoPlayer = Proxy.newProxyInstance(
        ExoPlayer::class.java.classLoader,
        arrayOf(ExoPlayer::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getPlayWhenReady", "isPlaying" -> true
            "getMediaItemCount" -> 1
            else -> error("Car binding must not call Media3 ${method.name}")
        }
    } as ExoPlayer
}
