package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Exercises the real event-bridge route from callback evidence to one-shot authorization. */
class CarContinuityEventAdapterTest {
    private val headset = AudioOutput(
        id = "bluetooth:only-headset",
        displayName = "Headset",
        kind = DeviceKind.Bluetooth,
        role = AudioOutputRole.Ambiguous,
        isActive = true,
    )

    private fun inputs(): CarContinuityRecoveryCoordinator.Inputs {
        val owner = RouteHeardOwnership().apply {
            onBookChanged(hasBook = true)
            onPlaybackObserved(listOf(headset))
        }
        return CarContinuityRecoveryCoordinator.Inputs(
            heardRoute = owner.heardRoute,
            headsetId = headset.id,
            generation = owner.currentGeneration,
            selectionSequence = 0,
            carConnected = false,
        )
    }

    @Test
    fun focusOrNoisyBeforeOrAfterArrivalCannotAuthorizeMoreThanOnce() {
        for (focus in listOf(true, false)) {
            for (arrivalFirst in listOf(true, false)) {
                val owner = CarContinuityRecoveryCoordinator(CarArrivalResumeGate())
                val before = inputs()
                owner.observePlayingHeadset(before)
                val verdict = if (arrivalFirst) {
                    assertEquals(
                        CarArrivalResumeGate.Status.Armed,
                        owner.carArrival(10.seconds, before.generation, 0).status,
                    )
                    owner.platformInterruption(
                        11.seconds,
                        before.copy(heardRoute = null, headsetId = null, carConnected = true),
                        focus,
                    )
                } else {
                    assertEquals(
                        CarArrivalResumeGate.Status.Armed,
                        owner.platformInterruption(10.seconds, before, focus).status,
                    )
                    owner.carArrival(11.seconds, before.generation, 0)
                }
                assertEquals(CarArrivalResumeGate.Status.Ready, verdict.status)
                val target = assertNotNull(verdict.target)
                assertEquals(headset.id, target.outputId)
                assertTrue(
                    owner.assess(target, CarContinuityPlayPrecheck.Proceed, before.generation, 0, headset.id).mayPlay,
                )
                assertFalse(
                    owner.assess(target, CarContinuityPlayPrecheck.Proceed, before.generation, 0, headset.id).mayPlay,
                )
            }
        }
    }

    @Test
    fun laterSelectionOrExplicitPauseCannotReviveOlderBoundary() {
        val first = inputs()
        val selected = CarContinuityRecoveryCoordinator(CarArrivalResumeGate())
        selected.observePlayingHeadset(first)
        assertEquals(
            CarArrivalResumeGate.Status.Armed,
            selected.platformInterruption(10.seconds, first, true).status,
        )
        assertEquals(
            CarArrivalResumeGate.Status.Rejected,
            selected.carArrival(11.seconds, first.generation, 1).status,
        )

        val paused = CarContinuityRecoveryCoordinator(CarArrivalResumeGate())
        paused.observePlayingHeadset(first)
        paused.platformInterruption(10.seconds, first, false)
        paused.cancelAll()
        assertEquals(
            CarArrivalResumeGate.Status.Rejected,
            paused.carArrival(11.seconds, first.generation, 0).status,
        )
    }

    @Test
    fun physicalProjectionDepartureAndLegacyControllerHaveDifferentAuthority() {
        val state = inputs()
        val physical = CarContinuityRecoveryCoordinator(CarArrivalResumeGate())
        physical.observePlayingHeadset(state)
        assertEquals(
            CarArrivalResumeGate.Status.Armed,
            physical.carDeparture(12.seconds, state.generation, 0, physicalProjection = true).status,
        )
        val legacy = CarContinuityRecoveryCoordinator(CarArrivalResumeGate())
        legacy.observePlayingHeadset(state)
        assertEquals(
            CarArrivalResumeGate.Status.Rejected,
            legacy.carDeparture(12.seconds, state.generation, 0, physicalProjection = false).status,
        )
    }
}
