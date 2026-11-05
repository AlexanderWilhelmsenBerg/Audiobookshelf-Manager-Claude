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
        val owner = RouteHeardOwnership(OutputIntentOwner()).apply {
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
            measured = { id -> fixtureHeadsetMeasured(id, listOf(headset)) },
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
            measured = { id -> fixtureHeadsetMeasured(id, listOf(headset)) },
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

    @Test
    fun staleCompletionCannotConsumeOrClearANewerEpisode() {
        for (precheck in CarContinuityPlayPrecheck.entries) {
            val (coordinator, old) = ready()
            val newer = nextDeparture(coordinator, old)
            coordinator.notePlayIssued(newer)

            assertFalse(coordinator.assess(old, precheck, old.generation, 0, headset.id).mayPlay)

            assertTrue(coordinator.isCurrent(newer, newer.generation, 0), "old $precheck must leave the new claim")
            assertTrue(coordinator.isPlayAwaiting, "old $precheck must leave the new diagnostic record")
            assertTrue(
                coordinator.assess(newer, CarContinuityPlayPrecheck.Proceed, newer.generation, 0, headset.id).mayPlay,
            )
            assertEquals(newer, coordinator.playbackBecameActive(newer.generation, 0))
        }
    }

    @Test
    fun equalDestinationAndGenerationDoNotReviveAnEarlierEpisode() {
        val (coordinator, old) = ready()
        nextDeparture(coordinator, old)
        coordinator.carArrival(14.seconds, old.generation, 0)
        val newer = requireNotNull(
            coordinator.platformInterruption(15.seconds, inputs(old), focusLoss = true).target,
        )
        assertEquals(old, newer, "route identity is unchanged; the owner-issued claim is new")
        assertFalse(coordinator.isCurrent(old, old.generation, 0), "an old callback cannot borrow the new claim")
        assertFalse(coordinator.assess(old, CarContinuityPlayPrecheck.Proceed, old.generation, 0, headset.id).mayPlay)
        assertTrue(
            coordinator.assess(newer, CarContinuityPlayPrecheck.Proceed, newer.generation, 0, headset.id).mayPlay,
        )
    }

    private fun nextDeparture(
        coordinator: CarContinuityRecoveryCoordinator,
        previous: CarArrivalResumeGate.Target,
    ): CarArrivalResumeGate.Target {
        coordinator.carDeparture(12.seconds, previous.generation, 0, physicalProjection = true)
        val decision = coordinator.platformInterruption(13.seconds, inputs(previous), focusLoss = true)
        assertEquals(CarArrivalResumeGate.Status.Ready, decision.status)
        return requireNotNull(decision.target)
    }

    private fun inputs(target: CarArrivalResumeGate.Target) = CarContinuityRecoveryCoordinator.Inputs(
        heardRoute = null,
        headsetId = null,
        generation = target.generation,
        selectionSequence = 0,
        carConnected = false,
    )
}
