package com.example.shelfplayer.playback

import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        val effects = CarContinuityResumeEffects(
            recovery = coordinator,
            outputs = AudioOutputRouter(
                context = RuntimeEnvironment.getApplication(),
                logger = logger,
                applicationScope = this,
                mainDispatcher = StandardTestDispatcher(testScheduler),
            ),
            owner = RouteHeardOwnership(),
            trace = AndroidAutoTraceRecorder(logger, TestAppClock()) { emptyList() },
            currentPlayer = { player },
            selectionSequence = { 0 },
            snapshot = { emptyList() },
            onServiceResumeIntent = { error("Active playback must not ask the sleep timer to resume") },
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
        val effects = CarContinuityResumeEffects(
            recovery = CarContinuityRecoveryCoordinator(CarArrivalResumeGate()),
            outputs = AudioOutputRouter(
                context = RuntimeEnvironment.getApplication(),
                logger = logger,
                applicationScope = this,
                mainDispatcher = StandardTestDispatcher(testScheduler),
            ),
            owner = RouteHeardOwnership(),
            trace = AndroidAutoTraceRecorder(logger, TestAppClock()) { emptyList() },
            currentPlayer = { null },
            selectionSequence = { 0 },
            snapshot = { emptyList() },
            onServiceResumeIntent = {},
        )
        effects.logHeldHeadset("bluetooth:private-fixture-id")
        val values = events.last().fields.filterIsInstance<LogField.Public>().associate { it.key to it.value }
        assertEquals("bluetooth", values["kind"])
        assertEquals("route-hold", values["phase"])
        assertTrue(values.values.none { it.contains("private-fixture-id") })
    }
}
