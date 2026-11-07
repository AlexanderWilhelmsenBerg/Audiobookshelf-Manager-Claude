package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputActivitySource
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The two independent playback proofs may arrive in either callback order. */
class CarContinuityRecoveryEvidenceOrderTest {
    @Test
    fun activePlaybackBeforeExactTrackProofStillConfirmsTheIssuedAttempt() {
        val headset = AudioOutput(
            id = "bluetooth:headset",
            displayName = "Headset",
            kind = DeviceKind.Bluetooth,
            activitySource = AudioOutputActivitySource.PlayingAudioTrack,
            role = AudioOutputRole.Ambiguous,
            isActive = true,
        )
        val owner = RouteHeardOwnership(OutputIntentOwner()).apply {
            onBookChanged(hasBook = true)
            onPlaybackObserved(listOf(headset))
        }
        val coordinator = CarContinuityRecoveryCoordinator(
            CarArrivalResumeGate({ Duration.ZERO }, { true }),
        )
        val outcomes = mutableListOf<CarContinuityRecoveryCoordinator.PlayOutcome>()
        coordinator.onPlayOutcome = outcomes::add
        val inputs = CarContinuityRecoveryCoordinator.Inputs(
            heardRoute = owner.heardRoute,
            headsetId = headset.id,
            generation = owner.currentGeneration,
            selectionSequence = 0,
            carConnected = false,
        )
        coordinator.observePlayingHeadset(inputs)
        coordinator.platformInterruption(10.seconds, inputs, focusLoss = true)
        val target = requireNotNull(coordinator.carArrival(11.seconds, owner.currentGeneration, 0).target)
        assertTrue(
            coordinator.assess(target, CarContinuityPlayPrecheck.Proceed, target.generation, 0, headset.id).mayPlay,
        )
        coordinator.notePlayIssued(target)

        // Media3 can report isPlaying before the route observer emits the exact AudioTrack sink.
        assertEquals(target, coordinator.playbackBecameActive(target.generation, 0))
        coordinator.observePlayingHeadset(inputs)
        assertTrue(outcomes.last() is CarContinuityRecoveryCoordinator.PlayOutcome.AudibleConfirmed)

        val rearmed = coordinator.routeSafetyPause(
            12.seconds,
            inputs.copy(headsetId = null, carConnected = true),
        )
        assertNotNull(rearmed, "active playback plus later exact route proof must confirm this attempt")
        assertEquals(2, rearmed.target?.attemptOrdinal)
    }

    @Test
    fun lateConfirmedArrivalDoesNotEraseEvidenceForANewDeparture() = confirmedArrivalThenDeparture(false)

    @Test
    fun lateConfirmedArrivalAllowsProjectionDepartureBeforeFocusLoss() = confirmedArrivalThenDeparture(true)

    @Test
    fun confirmedArrivalAllowsDepartureBeforeItsBudgetExpires() = confirmedArrivalThenDeparture(false, 20.seconds)

    private fun confirmedArrivalThenDeparture(projectionFirst: Boolean, departureAt: Duration = 45.seconds) {
        var now = Duration.ZERO
        val headset = AudioOutput(
            id = "bluetooth:headset",
            displayName = "Headset",
            kind = DeviceKind.Bluetooth,
            activitySource = AudioOutputActivitySource.PlayingAudioTrack,
            role = AudioOutputRole.Ambiguous,
            isActive = true,
        )
        val owner = RouteHeardOwnership(OutputIntentOwner()).apply {
            onBookChanged(hasBook = true)
            onPlaybackObserved(listOf(headset))
        }
        val coordinator = CarContinuityRecoveryCoordinator(
            CarArrivalResumeGate({ now }, { true }),
        )
        val arrivalInputs = CarContinuityRecoveryCoordinator.Inputs(
            heardRoute = owner.heardRoute,
            headsetId = headset.id,
            generation = owner.currentGeneration,
            selectionSequence = 0,
            carConnected = false,
        )
        coordinator.observePlayingHeadset(arrivalInputs)
        coordinator.platformInterruption(1.seconds, arrivalInputs, focusLoss = true)
        val arrival = requireNotNull(coordinator.carArrival(2.seconds, owner.currentGeneration, 0).target)
        assertTrue(
            coordinator.assess(arrival, CarContinuityPlayPrecheck.Proceed, arrival.generation, 0, headset.id).mayPlay,
        )
        coordinator.notePlayIssued(arrival)
        coordinator.playbackBecameActive(arrival.generation, 0)
        coordinator.observePlayingHeadset(arrivalInputs)

        now = departureAt
        if (projectionFirst) {
            val boundary = coordinator.carDeparture(now, owner.currentGeneration, 0, physicalProjection = true)
            assertEquals(CarArrivalResumeGate.Status.Armed, boundary.status)
            now = departureAt + 0.3.seconds
            val focus = coordinator.platformInterruption(now, arrivalInputs, focusLoss = true)
            assertEquals(CarArrivalResumeGate.Status.Ready, focus.status)
            assertEquals(CarArrivalResumeGate.Phase.Departure, focus.phase)
            return
        }
        val departureInputs = arrivalInputs.copy(carConnected = true)
        val focus = coordinator.platformInterruption(now, departureInputs, focusLoss = true)
        assertEquals(CarArrivalResumeGate.Status.Armed, focus.status)

        now = departureAt + 0.3.seconds
        val departure = coordinator.carDeparture(
            now,
            owner.currentGeneration,
            0,
            physicalProjection = true,
        )
        assertEquals(CarArrivalResumeGate.Status.Ready, departure.status)
        assertEquals(CarArrivalResumeGate.Phase.Departure, departure.phase)
    }
}
