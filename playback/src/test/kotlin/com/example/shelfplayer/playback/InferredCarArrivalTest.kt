package com.example.shelfplayer.playback

import android.media.AudioManager
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputActivitySource
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.playback.CarArrivalResumeGate.Reason
import com.example.shelfplayer.playback.CarArrivalResumeGate.Status
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PD-009 refinement 2026-11-16: a car-class hands-free link plus a measured focus loss of the trusted playing
 * headset may stand in for the Android Auto boundary, which hardware put three or more seconds later.
 */
class InferredCarArrivalTest {
    private val buds = AudioOutput(
        id = "bluetooth:buds",
        displayName = "Buds",
        kind = DeviceKind.Bluetooth,
        role = AudioOutputRole.Ambiguous,
    )
    private var now: Duration = Duration.ZERO
    private var trusted = true
    private var mode: Int? = AudioManager.MODE_NORMAL
    private var modeReads = 0
    private val owner = RouteHeardOwnership(OutputIntentOwner()).apply { onBookChanged(hasBook = true) }
    private val gate = CarArrivalResumeGate(now = { now }, trustedIdentity = { trusted && it == buds.id })
    private val window = CarBluetoothArrivalWindow { now }
    private val logged = mutableListOf<String>()
    private val inference = CarBluetoothArrivalInference(
        window = window,
        gate = gate,
        audioMode = {
            modeReads++
            mode
        },
        now = { now },
    ) { _, fields ->
        logged += fields.filterIsInstance<LogField.Public>().single { it.key == "outcome" }.value
    }

    @Test
    fun `focus loss inside the car window resumes without waiting for the Auto bind`() {
        hearHeadset()
        window.onCarHandsFreeConnected("car")

        val inferred = assertNotNull(infer(focusLoss(at = 10.seconds)))

        assertEquals(Status.Ready, inferred.status)
        assertEquals(Reason.CarBluetoothMatchedFocusLoss, inferred.reason)
        val target = assertNotNull(inferred.target)
        assertEquals(buds.id, target.outputId)
        assertTrue(target.inferredArrival)
        assertEquals(40.seconds, target.deadline, "the deadline is the original interruption's")
        assertNull(window.age(), "one window supports one inferred arrival")
        assertEquals(listOf("resume-issued"), logged)
    }

    @Test
    fun `a ringing or active call is never mistaken for Android Auto`() {
        for (blocked in listOf(AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, null)) {
            hearHeadset()
            window.onCarHandsFreeConnected("car")
            mode = blocked

            assertNull(infer(focusLoss(at = now + 1.seconds)))
            assertNotNull(window.age(), "a blocked evaluation does not spend the window")
            gate.cancelAll()
        }
        assertEquals(List(3) { "blocked-by-audio-mode" }, logged)
    }

    @Test
    fun `without the car window the focus loss keeps waiting for the Auto boundary`() {
        hearHeadset()

        val decision = focusLoss(at = 10.seconds)

        assertNull(infer(decision))
        assertEquals(0, modeReads, "nothing is read when no car link was seen")
        assertEquals(Status.Ready, gate.onCarArrival(13.seconds, owner.currentGeneration, 0).status)
    }

    @Test
    fun `an untrusted headset cannot use the inference`() {
        trusted = false
        hearHeadset()
        window.onCarHandsFreeConnected("car")

        assertNull(infer(focusLoss(at = 10.seconds)))
        assertEquals(listOf("not-qualified"), logged)
        assertNotNull(window.age())
    }

    @Test
    fun `the later Auto bind keeps the inferred claim and cannot issue a second Play`() {
        hearHeadset()
        window.onCarHandsFreeConnected("car")
        val target = assertNotNull(infer(focusLoss(at = 10.seconds))?.target)

        val bind = gate.onCarArrival(13.seconds, owner.currentGeneration, 0)
        assertEquals(Status.Ready, bind.status)
        assertSame(target, bind.target)

        assertTrue(gate.consumeRecovery(target, owner.currentGeneration, buds.id, 0).accepted)
        assertFalse(gate.consumeRecovery(target, owner.currentGeneration, buds.id, 0).accepted)
        assertEquals(Status.Rejected, gate.onCarArrival(14.seconds, owner.currentGeneration, 0).status)
    }

    @Test
    fun `an inferred Play taken straight back may retry before the Auto bind`() {
        hearHeadset()
        window.onCarHandsFreeConnected("car")
        val target = assertNotNull(infer(focusLoss(at = 10.seconds))?.target)
        assertTrue(gate.consumeRecovery(target, owner.currentGeneration, buds.id, 0).accepted)
        gate.markRecoveryPlayIssued(target)

        val bounced = focusLoss(at = 11.seconds)

        assertEquals(Status.Ready, bounced.status)
        assertEquals(Reason.UnconfirmedPlayInterrupted, bounced.reason)
        assertEquals(2, bounced.target?.attemptOrdinal)
        assertEquals(true, bounced.target?.retryAfterFocusLoss)
    }

    @Test
    fun `listener cancellation removes the inferred claim`() {
        hearHeadset()
        window.onCarHandsFreeConnected("car")
        val target = assertNotNull(infer(focusLoss(at = 10.seconds))?.target)

        gate.cancelAll()

        assertFalse(gate.isCurrent(target, owner.currentGeneration, 0))
        assertFalse(gate.consumeRecovery(target, owner.currentGeneration, buds.id, 0).accepted)
    }

    @Test
    fun `a focus loss that is not waiting for a boundary is left alone`() {
        window.onCarHandsFreeConnected("car")

        val decision = focusLoss(at = 10.seconds)

        assertEquals(Reason.NoQualifyingHeadset, decision.reason)
        assertNull(infer(decision))
        assertTrue(logged.isEmpty())
    }

    private fun infer(decision: CarArrivalResumeGate.Decision) =
        inference.onFocusLoss(decision, owner.currentGeneration, 0)

    private fun hearHeadset() {
        val rows = listOf(buds.copy(isActive = true, activitySource = AudioOutputActivitySource.PlayingAudioTrack))
        owner.onPlaybackObserved(rows)
        gate.observePlayingHeadset(owner.heardRoute, owner.headsetForCar(rows), owner.currentGeneration, 0)
    }

    private fun focusLoss(at: Duration): CarArrivalResumeGate.Decision {
        now = at
        return gate.onAudioFocusLoss(at, owner.heardRoute, buds.id, owner.currentGeneration, 0, carConnected = false)
    }
}
