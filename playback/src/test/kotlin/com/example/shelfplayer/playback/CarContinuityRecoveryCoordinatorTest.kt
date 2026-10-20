package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Behavioral acceptance over the production gate, route recovery and final claim coordinator. */
class CarContinuityRecoveryCoordinatorTest {
    private val headset = AudioOutput(
        id = "bluetooth:headset",
        displayName = "Headset",
        kind = DeviceKind.Bluetooth,
        role = AudioOutputRole.Ambiguous,
        isActive = true,
    )

    private fun ready(): Pair<CarContinuityRecoveryCoordinator, CarArrivalResumeGate.Target> {
        val owner = RouteHeardOwnership().apply {
            onBookChanged(hasBook = true)
            onPlaybackObserved(listOf(headset))
        }
        val gate = CarArrivalResumeGate()
        val coordinator = CarContinuityRecoveryCoordinator(gate, CarArrivalRouteRecovery(50.milliseconds))
        gate.observePlayingHeadset(owner.heardRoute, headset.id, owner.currentGeneration, 0)
        val focus = gate.onAudioFocusLoss(
            at = 10.seconds,
            heardRoute = owner.heardRoute,
            headsetId = headset.id,
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
            carConnected = false,
        )
        assertEquals(CarArrivalResumeGate.Status.Armed, focus.status)
        val arrival = gate.onCarArrival(11.seconds, owner.currentGeneration, 0)
        assertEquals(CarArrivalResumeGate.Status.Ready, arrival.status)
        return coordinator to requireNotNull(arrival.target)
    }

    @Test
    fun oneRecoveryClaimCanAuthorizeExactlyOnePlay() {
        val (coordinator, target) = ready()
        val first = coordinator.assess(target, CarContinuityPlayPrecheck.Proceed, target.generation, 0, headset.id)
        val repeated = coordinator.assess(target, CarContinuityPlayPrecheck.Proceed, target.generation, 0, headset.id)
        assertTrue(first.mayPlay)
        assertFalse(repeated.mayPlay)
        assertEquals(CarArrivalResumeGate.Reason.CandidateInvalidated, repeated.consumed?.reason)
    }

    @Test
    fun explicitPauseAndEveryPrecheckInvalidateRecovery() {
        for (precheck in CarContinuityPlayPrecheck.entries) {
            if (precheck == CarContinuityPlayPrecheck.Proceed) continue
            val (coordinator, target) = ready()
            val ignored = coordinator.assess(target, precheck, target.generation, 0, headset.id)
            assertFalse(ignored.mayPlay)
            assertFalse(
                coordinator.assess(target, CarContinuityPlayPrecheck.Proceed, target.generation, 0, headset.id).mayPlay,
            )
        }
        val (coordinator, target) = ready()
        coordinator.cancelAll()
        assertFalse(
            coordinator.assess(
                target,
                CarContinuityPlayPrecheck.Proceed,
                target.generation,
                0,
                headset.id,
            ).mayPlay,
        )
    }

    @Test
    fun generationSelectionAndHeadsetMismatchCannotBorrowRecovery() {
        for (change in 0..2) {
            val (coordinator, target) = ready()
            val result = when (change) {
                0 -> coordinator.assess(target, CarContinuityPlayPrecheck.Proceed, target.generation + 1, 0, headset.id)

                1 -> coordinator.assess(target, CarContinuityPlayPrecheck.Proceed, target.generation, 1, headset.id)

                else -> coordinator.assess(
                    target,
                    CarContinuityPlayPrecheck.Proceed,
                    target.generation,
                    0,
                    "other-headset",
                )
            }
            assertFalse(result.mayPlay)
        }
    }

    @Test
    fun recoveryRouteMustStayOnCapturedHeadsetAndRespectNewerIntent() = runBlocking {
        val (coordinator, target) = ready()
        var reassertions = 0
        val secured = coordinator.secure(
            target = target,
            outputs = MutableStateFlow(listOf(headset)),
            selectedId = MutableStateFlow<String?>(null),
            currentGeneration = { target.generation },
            selectionSequence = { 0 },
        ) {
            reassertions++
            true
        }
        assertEquals(headset.id, secured)
        assertEquals(0, reassertions)

        val (stale, staleTarget) = ready()
        val denied = stale.secure(
            target = staleTarget,
            outputs = MutableStateFlow(listOf(headset)),
            selectedId = MutableStateFlow<String?>(null),
            currentGeneration = { staleTarget.generation },
            selectionSequence = { 1 },
        ) {
            reassertions++
            true
        }
        assertNull(denied)
        assertEquals(0, reassertions)
    }

    @Test
    fun diagnosticPendingClaimNeverAuthorizesOrSurvivesMismatch() {
        val (coordinator, target) = ready()
        coordinator.notePlayIssued(target)
        assertTrue(coordinator.isPlayAwaiting)
        assertNull(coordinator.playbackBecameActive(target.generation, 1))
        assertFalse(coordinator.isPlayAwaiting)
        coordinator.notePlayIssued(target)
        assertEquals(target, coordinator.playbackBecameActive(target.generation, 0))
        assertFalse(coordinator.isPlayAwaiting)
        coordinator.notePlayIssued(target)
        coordinator.cancelPending()
        assertTrue(coordinator.isPlayAwaiting, "Media3 playWhenReady must not swallow the audible confirmation")
        assertEquals(target, coordinator.playbackBecameActive(target.generation, 0))
        coordinator.notePlayIssued(target)
        coordinator.cancelAll()
        assertFalse(coordinator.isPlayAwaiting)
        assertNull(coordinator.playbackBecameActive(target.generation, 0))
    }
}
