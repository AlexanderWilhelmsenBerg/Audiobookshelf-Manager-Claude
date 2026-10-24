package com.example.shelfplayer.playback

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
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            ),
            owner = RouteHeardOwnership(),
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
            ),
            owner = RouteHeardOwnership(),
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
            val owner = RouteHeardOwnership().apply {
                onBookChanged(hasBook = true)
                onPlaybackObserved(listOf(headset))
            }
            val gate = CarArrivalResumeGate()
            val recovery = CarContinuityRecoveryCoordinator(gate, CarArrivalRouteRecovery(250.milliseconds))
            gate.observePlayingHeadset(owner.heardRoute, headset.id, owner.currentGeneration, 0)
            assertEquals(
                CarArrivalResumeGate.Status.Armed,
                gate.onAudioFocusLoss(
                    at = 10.seconds,
                    heardRoute = owner.heardRoute,
                    headsetId = headset.id,
                    currentGeneration = owner.currentGeneration,
                    explicitSelectionSequence = 0,
                    carConnected = false,
                ).status,
            )
            val arrival = gate.onCarArrival(11.seconds, owner.currentGeneration, 0)
            assertEquals(CarArrivalResumeGate.Status.Ready, arrival.status)
            val target = requireNotNull(arrival.target)

            var selection = 0L
            val events = mutableListOf<LogEvent>()
            val logger = object : Logger {
                override fun log(event: LogEvent) {
                    events += event
                }
            }
            val recorder = AndroidAutoTraceRecorder(logger, TestAppClock()) { emptyList() }
            val player = Proxy.newProxyInstance(
                ExoPlayer::class.java.classLoader,
                arrayOf(ExoPlayer::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getPlayWhenReady" -> false
                    "getMediaItemCount" -> 1
                    else -> error("Stale recovery must not call Media3 ${method.name}")
                }
            } as ExoPlayer
            val effects = CarContinuityResumeEffects(
                recovery = recovery,
                outputs = AudioOutputRouter(
                    context = RuntimeEnvironment.getApplication(),
                    logger = logger,
                    applicationScope = this,
                    mainDispatcher = StandardTestDispatcher(testScheduler),
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
}
