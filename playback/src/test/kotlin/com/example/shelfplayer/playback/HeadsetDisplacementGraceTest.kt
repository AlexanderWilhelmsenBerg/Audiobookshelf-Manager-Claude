package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputActivitySource
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * PLAY-002 / #277, APK 2272: Android can measure the playing track on the phone speaker for a moment while it
 * swaps the active Bluetooth media device. One such sample must not erase the verified headset before the
 * safety pause that follows, and must still count as a real route move when no interruption arrives.
 */
class HeadsetDisplacementGraceTest {
    private val buds = AudioOutput(
        id = "bluetooth:buds",
        displayName = "Buds",
        kind = DeviceKind.Bluetooth,
        role = AudioOutputRole.Ambiguous,
    )
    private val speaker = AudioOutput(
        id = "speaker:phone",
        displayName = "Phone speaker",
        kind = DeviceKind.Speaker,
        role = AudioOutputRole.Speaker,
    )
    private var now: Duration = Duration.ZERO
    private val owner = RouteHeardOwnership(OutputIntentOwner()).apply { onBookChanged(hasBook = true) }
    private val gate = CarArrivalResumeGate(now = { now }, trustedIdentity = { it == buds.id })

    @Test
    fun `speaker sample followed by a safety pause keeps the headset return claim`() {
        hearHeadset()
        hearSpeaker(at = 10.seconds)

        now = 10.seconds + 70.milliseconds
        val decision = gate.onRouteSafetyPause(
            at = now,
            headsetId = null,
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
        )

        assertEquals(CarArrivalResumeGate.Reason.SafetyPausedHeadsetReturn, assertNotNull(decision).reason)
        assertEquals(buds.id, decision.target?.outputId)
        assertEquals(true, gate.retainsInterruptedHeadset(buds.id, owner.currentGeneration, 0))
    }

    @Test
    fun `speaker sample followed by focus loss still arms the headset for a car boundary`() {
        hearHeadset()
        hearSpeaker(at = 10.seconds)

        val focus = focusLoss(at = 11.seconds)

        assertEquals(CarArrivalResumeGate.Status.Armed, focus.status)
        assertEquals(buds.id, focus.target?.outputId)
    }

    @Test
    fun `speaker route that outlives the grace is a real move and clears the headset`() {
        hearHeadset()
        hearSpeaker(at = 10.seconds)

        assertNull(gate.onRouteSafetyPause(12.seconds, null, owner.currentGeneration, 0))
        assertEquals(CarArrivalResumeGate.Reason.NoQualifyingHeadset, focusLoss(at = 13.seconds).reason)
    }

    @Test
    fun `repeated speaker samples cannot extend the grace`() {
        hearHeadset()
        hearSpeaker(at = 10.seconds)
        hearSpeaker(at = 11.seconds)
        hearSpeaker(at = 12.seconds + 100.milliseconds)

        assertNull(gate.onRouteSafetyPause(12.seconds + 200.milliseconds, null, owner.currentGeneration, 0))
    }

    @Test
    fun `headset measured again ends the grace without losing it`() {
        hearHeadset()
        hearSpeaker(at = 10.seconds)
        now = 11.seconds
        hearHeadset()

        assertEquals(CarArrivalResumeGate.Status.Armed, focusLoss(at = 30.seconds).status)
    }

    private fun hearHeadset() {
        val rows = listOf(buds.copy(isActive = true, activitySource = AudioOutputActivitySource.PlayingAudioTrack))
        owner.onPlaybackObserved(rows)
        gate.observePlayingHeadset(owner.heardRoute, owner.headsetForCar(rows), owner.currentGeneration, 0)
    }

    private fun hearSpeaker(at: Duration) {
        now = at
        val rows = listOf(
            speaker.copy(isActive = true, activitySource = AudioOutputActivitySource.PlayingAudioTrack),
            buds.copy(isActive = false, activitySource = AudioOutputActivitySource.PlayingAudioTrack),
        )
        owner.onOutputsChanged(rows, isPlaying = true)
        gate.observePlayingHeadset(owner.heardRoute, owner.headsetForCar(rows), owner.currentGeneration, 0)
    }

    private fun focusLoss(at: Duration): CarArrivalResumeGate.Decision {
        now = at
        return gate.onAudioFocusLoss(at, owner.heardRoute, null, owner.currentGeneration, 0, carConnected = false)
    }
}
