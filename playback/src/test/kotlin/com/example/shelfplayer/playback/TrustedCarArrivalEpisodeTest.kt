package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Bounded delayed arrival recovery requires a trusted, previously heard exact endpoint. */
class TrustedCarArrivalEpisodeTest {
    private val headset = AudioOutput(
        id = "bt:process-private-token",
        displayName = "Headset",
        kind = DeviceKind.Bluetooth,
        role = AudioOutputRole.Ambiguous,
        isActive = true,
    )

    @Test
    fun `qualified arrival accepts delayed bind but deadline starts at first safety event`() {
        var now = Duration.ZERO
        val gate = gate(now = { now }, trusted = { it == headset.id })

        gate.onRouteSafetyPause(0.seconds, headset.id, 1, 0, carConnected = false)
        now = 5.seconds
        val focus = gate.onAudioFocusLoss(
            at = now,
            heardRoute = null,
            headsetId = null,
            currentGeneration = 1,
            explicitSelectionSequence = 0,
            carConnected = false,
        )
        assertEquals(CarArrivalResumeGate.Status.Armed, focus.status)
        assertFalse(gate.isCurrent(assertNotNull(focus.target), 1, 0), "the car boundary is still required")

        // A repeated callback does not start a fresh 30-second interval.
        now = 9.seconds
        gate.onAudioBecomingNoisy(
            at = now,
            heardRoute = null,
            headsetId = null,
            currentGeneration = 1,
            explicitSelectionSequence = 0,
            carConnected = false,
        )

        now = 10_600.milliseconds
        val arrival = gate.onCarArrival(now, currentGeneration = 1, explicitSelectionSequence = 0)
        val target = assertNotNull(arrival.target)

        assertEquals(CarArrivalResumeGate.Status.Ready, arrival.status)
        assertEquals(30.seconds, target.deadline)
        assertTrue(gate.episodeValid(target))
        assertEquals(19_400.milliseconds, gate.remainingWindow(target))
        now = 29.seconds
        assertTrue(gate.isCurrent(target, 1, 0))
        now = 30_001.milliseconds
        assertFalse(gate.isCurrent(target, 1, 0))
        assertEquals(
            CarArrivalResumeGate.Reason.RecoveryExpired,
            gate.consumeRecovery(target, 1, headset.id, 0).reason,
        )
    }

    @Test
    fun `legacy or untrusted focus keeps the six-second arrival bound`() {
        var now = Duration.ZERO
        val gate = gate(now = { now }, trusted = { false })

        gate.onRouteSafetyPause(0.seconds, headset.id, 1, 0, carConnected = false)
        now = 5.seconds
        gate.onAudioFocusLoss(
            at = now,
            heardRoute = null,
            headsetId = null,
            currentGeneration = 1,
            explicitSelectionSequence = 0,
            carConnected = false,
        )
        now = 10_600.milliseconds

        val arrival = gate.onCarArrival(now, currentGeneration = 1, explicitSelectionSequence = 0)

        assertEquals(CarArrivalResumeGate.Status.Rejected, arrival.status)
        assertEquals(CarArrivalResumeGate.Reason.NoPendingFocusLoss, arrival.reason)
    }

    @Test
    fun `permission granted while paused cannot qualify previously untrusted heard identity`() {
        var now = Duration.ZERO
        var trusted = false
        val gate = gate(now = { now }, trusted = { trusted })
        gate.onRouteSafetyPause(0.seconds, headset.id, 1, 0, carConnected = false)
        trusted = true
        now = 5.seconds
        gate.onAudioFocusLoss(
            at = now,
            heardRoute = null,
            headsetId = null,
            currentGeneration = 1,
            explicitSelectionSequence = 0,
            carConnected = false,
        )
        now = 10_600.milliseconds

        val arrival = gate.onCarArrival(now, 1, 0)

        assertEquals(CarArrivalResumeGate.Status.Rejected, arrival.status)
        assertEquals(CarArrivalResumeGate.Reason.NoPendingFocusLoss, arrival.reason)
    }

    @Test
    fun `revoked trusted identity cannot keep an issued recovery alive`() {
        var now = Duration.ZERO
        var trusted = true
        val gate = gate(now = { now }, trusted = { trusted })
        val target = readyTarget(gate, { now = it })

        now = 11.seconds
        trusted = false

        assertFalse(gate.isCurrent(target, 1, 0))
        assertEquals(
            CarArrivalResumeGate.Reason.IdentityUntrusted,
            gate.consumeRecovery(target, 1, headset.id, 0).reason,
        )
    }

    @Test
    fun `duplicate focus and bind preserve the exact active recovery target`() {
        var now = Duration.ZERO
        val gate = gate(now = { now }, trusted = { it == headset.id })
        val target = readyTarget(gate, { now = it })
        now = 11.seconds

        val focus = gate.onAudioFocusLoss(
            at = now,
            heardRoute = null,
            headsetId = null,
            currentGeneration = 1,
            explicitSelectionSequence = 0,
            carConnected = false,
        )
        val bind = gate.onCarArrival(12.seconds, 1, 0)

        assertEquals(CarArrivalResumeGate.Status.Ready, focus.status)
        assertSame(target, focus.target)
        assertEquals(CarArrivalResumeGate.Status.Ready, bind.status)
        assertSame(target, bind.target)
        assertTrue(gate.isCurrent(target, 1, 0))
    }

    @Test
    fun `consumed episode cannot mint another arrival after projection departure`() {
        var now = Duration.ZERO
        val gate = gate(now = { now }, trusted = { it == headset.id })
        val target = readyTarget(gate, { now = it })

        assertTrue(gate.consumeRecovery(target, 1, headset.id, 0).accepted)
        val departure = gate.onProjectionDeparture(14_600.milliseconds, 1, 0)
        assertFalse(gate.episodeValid(target), "closed arrival cannot unmute an already-issued probe")
        now = 15.seconds
        val focus = gate.onAudioFocusLoss(
            at = now,
            heardRoute = null,
            headsetId = null,
            currentGeneration = 1,
            explicitSelectionSequence = 0,
            carConnected = false,
        )
        val arrival = gate.onCarArrival(16.seconds, 1, 0)

        assertEquals(CarArrivalResumeGate.Status.Rejected, departure.status)
        assertEquals(CarArrivalResumeGate.Status.Rejected, focus.status)
        assertEquals(CarArrivalResumeGate.Status.Rejected, arrival.status)
        assertEquals(CarArrivalResumeGate.Reason.CandidateInvalidated, arrival.reason)
        assertNull(arrival.target)
    }

    @Test
    fun `departure pair remains six seconds and closes the arrival extension`() {
        var now = Duration.ZERO
        val gate = gate(now = { now }, trusted = { it == headset.id })
        gate.onRouteSafetyPause(0.seconds, headset.id, 1, 0, carConnected = false)
        now = 5.seconds
        gate.onAudioFocusLoss(
            at = now,
            heardRoute = null,
            headsetId = null,
            currentGeneration = 1,
            explicitSelectionSequence = 0,
            carConnected = true,
        )

        val departure = gate.onCarDeparture(10_600.milliseconds, 1, 0)

        assertEquals(CarArrivalResumeGate.Status.Ready, departure.status)
        now = 12.seconds
        gate.onAudioFocusLoss(
            at = now,
            heardRoute = null,
            headsetId = null,
            currentGeneration = 1,
            explicitSelectionSequence = 0,
            carConnected = false,
        )
        val lateArrival = gate.onCarArrival(19.seconds, 1, 0)
        assertEquals(CarArrivalResumeGate.Status.Rejected, lateArrival.status)
        assertEquals(CarArrivalResumeGate.Reason.OutsidePairingWindow, lateArrival.reason)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `recovery wait uses remaining qualified episode time after the ordinary route window`() = runTest {
        var now = Duration.ZERO
        val gate = gate(now = { now }, trusted = { it == headset.id })
        val target = readyTarget(gate, { now = it })
        val outputs = MutableStateFlow(emptyList<AudioOutput>())
        val selected = MutableStateFlow<String?>(null)
        val coordinator = CarContinuityRecoveryCoordinator(gate, CarArrivalRouteRecovery(20.milliseconds))
        var reassertions = 0

        now = 10_600.milliseconds
        val secured = async {
            coordinator.secure(
                target = target,
                outputs = outputs,
                selectedId = selected,
                currentGeneration = { 1L },
                selectionSequence = { 0L },
                measured = { outputs.value.any { output -> output.id == it } },
                reassert = {
                    reassertions++
                    true
                },
            )
        }
        yield()
        advanceTimeBy(3.seconds)
        runCurrent()
        now = 13_600.milliseconds
        outputs.value = listOf(headset)

        assertEquals(headset.id, secured.await())
        assertEquals(0, reassertions, "measured return is sufficient; no route rewrite is needed")
    }

    private fun gate(now: () -> Duration, trusted: (String) -> Boolean): CarArrivalResumeGate {
        val owner = RouteHeardOwnership(OutputIntentOwner()).apply {
            onBookChanged(hasBook = true)
            onPlaybackObserved(listOf(headset))
        }
        return CarArrivalResumeGate(now = now, trustedIdentity = trusted).also { gate ->
            gate.observePlayingHeadset(owner.heardRoute, headset.id, owner.currentGeneration, 0)
        }
    }

    private fun readyTarget(gate: CarArrivalResumeGate, setNow: (Duration) -> Unit): CarArrivalResumeGate.Target {
        gate.onRouteSafetyPause(0.seconds, headset.id, 1, 0, carConnected = false)
        setNow(5.seconds)
        gate.onAudioFocusLoss(
            at = 5.seconds,
            heardRoute = null,
            headsetId = null,
            currentGeneration = 1,
            explicitSelectionSequence = 0,
            carConnected = false,
        )
        setNow(10_600.milliseconds)
        return assertNotNull(gate.onCarArrival(10_600.milliseconds, 1, 0).target)
    }
}
