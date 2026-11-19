package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputActivitySource
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** PLAY-002: only an app safety interruption can authorize the short same-headset return. */
class HeadsetSafetyReturnTest {
    private class Fixture(trustedAtPlayback: Boolean = true) {
        var now = Duration.ZERO
        var trusted = trustedAtPlayback
        val owner = RouteHeardOwnership(OutputIntentOwner()).apply {
            onBookChanged(true)
            onPlaybackObserved(
                listOf(
                    AudioOutput(
                        id = "bluetooth:original",
                        displayName = "Fixture",
                        kind = DeviceKind.Bluetooth,
                        role = AudioOutputRole.Ambiguous,
                        activitySource = AudioOutputActivitySource.PlayingAudioTrack,
                        isActive = true,
                    ),
                ),
            )
        }
        val coordinator = CarContinuityRecoveryCoordinator(CarArrivalResumeGate({ now }, { trusted }))
        val inputs = CarContinuityRecoveryCoordinator.Inputs(owner.heardRoute, "bluetooth:original", 1, 0, false)
        init {
            coordinator.observePlayingHeadset(inputs)
        }
        fun pause() = coordinator.routeSafetyPause(now, inputs)
        fun claim(
            target: CarArrivalResumeGate.Target,
            generation: Long = 1,
            selection: Long = 0,
            id: String = inputs.headsetId!!,
        ) = coordinator.assess(target, CarContinuityPlayPrecheck.Proceed, generation, selection, id).mayPlay
    }

    @Test
    fun `safety pause permits exactly one same-headset recovery without car boundary`() {
        val f = Fixture()
        val target = assertNotNull(f.pause()?.target)
        assertEquals(2.seconds, target.deadline)
        f.now = 1500.milliseconds
        assertTrue(f.claim(target))
        f.coordinator.notePlayIssued(target)
        f.coordinator.cancelPending() // Media3's observed Play must not revoke its muted verification.
        assertTrue(f.coordinator.episodeValid(target))
        assertFalse(f.claim(target))
    }

    @Test
    fun `repeated safety callback does not restart the two second deadline`() {
        val f = Fixture()
        val target = assertNotNull(f.pause()?.target)
        f.now = 1500.milliseconds
        assertNull(f.pause())
        f.now = 2.seconds
        assertFalse(f.claim(target))
        assertFalse(f.coordinator.episodeValid(target))
        assertNull(f.pause())
    }

    @Test
    fun `new intent book identity and wrong destination invalidate recovery`() {
        for (scenario in 0..4) {
            val f = Fixture()
            val target = assertNotNull(f.pause()?.target)
            when (scenario) {
                0 -> {
                    f.coordinator.cancelAll()
                    assertFalse(f.claim(target))
                }

                1 -> assertFalse(f.claim(target, generation = 2))

                2 -> assertFalse(f.claim(target, selection = 1))

                3 -> assertFalse(f.claim(target, id = "bluetooth:other"))

                4 -> {
                    f.trusted = false
                    assertFalse(f.claim(target))
                }
            }
        }
        val untrusted = Fixture(trustedAtPlayback = false)
        assertNull(untrusted.pause())
    }

    @Test
    fun `focus loss cancels short recovery but preserves car boundary policy`() {
        val f = Fixture()
        val short = assertNotNull(f.pause()?.target)
        f.now = 500.milliseconds
        val focus = f.coordinator.platformInterruption(f.now, f.inputs, focusLoss = true)
        assertEquals(CarArrivalResumeGate.Status.Armed, focus.status)
        assertFalse(f.claim(short))
        f.now = 4.seconds
        val arrival = assertNotNull(f.coordinator.carArrival(f.now, 1, 0).target)
        assertTrue(f.claim(arrival))
    }

    @Test
    fun `focus loss alone never creates a boundary free recovery`() {
        val f = Fixture()
        val decision = f.coordinator.platformInterruption(f.now, f.inputs, focusLoss = true)
        assertEquals(CarArrivalResumeGate.Status.Armed, decision.status)
        assertFalse(f.claim(assertNotNull(decision.target)))
    }
}
