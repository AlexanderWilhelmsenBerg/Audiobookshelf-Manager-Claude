package com.example.shelfplayer.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Real service/guard callback and existing muted handoff, without a car connection event. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceHeadsetReturnTest {
    @Test
    fun `exact headset return resumes once and only verified route unmutes`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            startGap(f)
            assertEquals(CarArrivalResumeGate.Phase.HeadsetReturn, assertNotNull(f.pendingEffectsTarget()).phase)
            f.advanceElapsedClock(500.milliseconds)
            advanceTimeBy(500)
            f.returnMeasuredHeadset()
            runCurrent()
            assertEquals(1, f.rawPlayCalls)
            assertTrue(f.isRouteMuted(), "inventory/preference alone cannot unmute")
            f.observeAudibleServiceRoute()
            assertFalse(f.isRouteMuted())
            assertTrue(f.player.playWhenReady)
            f.returnMeasuredHeadset()
            runCurrent()
            assertEquals(1, f.rawPlayCalls, "duplicate inventory cannot issue another Play")
        }
    }

    @Test
    fun `headset returning after deadline stays paused`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            startGap(f)
            f.advanceElapsedClock(2.seconds)
            advanceTimeBy(2000)
            runCurrent()
            f.returnMeasuredHeadset()
            runCurrent()
            assertEquals(0, f.rawPlayCalls)
            assertFalse(f.player.playWhenReady)
        }
    }

    @Test
    fun `listener Pause cancels while waiting for the headset`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            startGap(f)
            assertNull(f.forwarding.handleSetPlayWhenReady(false).get())
            f.returnMeasuredHeadset()
            runCurrent()
            assertEquals(0, f.rawPlayCalls)
            assertFalse(f.player.playWhenReady)
        }
    }

    @Test
    fun `missing actual route cannot unmute and deadline stops the probe`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            startGap(f)
            f.returnMeasuredHeadset()
            f.service.audioOutputs.currentTrackObservation = { AudioTrackObservation(2, AudioTrackPath.RemoteSubmix) }
            runCurrent()
            assertEquals(1, f.rawPlayCalls)
            assertTrue(f.isRouteMuted())
            f.advanceElapsedClock(2.seconds)
            advanceTimeBy(2100)
            runCurrent()
            assertFalse(f.player.playWhenReady)
            assertEquals(1, f.rawPlayCalls, "probe failure must not retry itself")
        }
    }

    @Test
    fun `new dashboard destination wins during the muted return`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            startGap(f)
            f.chooseCarOnNextRouteMute()
            f.returnMeasuredHeadset()
            runCurrent()
            assertEquals(OutputIntentOwner.Destination.Car, f.explicitDestination())
            assertEquals(0, f.rawPlayCalls, "newer selection must invalidate the final Play")
            assertFalse(f.player.playWhenReady)
        }
    }

    @Test
    fun `car arrival while waiting cannot replace the short claim or duplicate Play`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            startGap(f)
            val target = assertNotNull(f.pendingEffectsTarget())
            val coordinator = f.service.playbackField<CarContinuityRecoveryCoordinator>("carRecovery")
            val effects = f.service.playbackCall("getCarResumeEffects") as CarContinuityResumeEffects
            repeat(2) {
                effects.onArrival(coordinator.carArrival(500.milliseconds, target.generation, 0))
            }
            f.returnMeasuredHeadset()
            runCurrent()
            assertEquals(1, f.rawPlayCalls)
            f.observeAudibleServiceRoute()
            assertTrue(f.player.playWhenReady)
        }
    }

    @Test
    fun `noisy event during the same gap retains the unconsumed claim without extending it`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            startGap(f)
            val original = assertNotNull(f.pendingEffectsTarget())
            f.advanceElapsedClock(500.milliseconds)
            advanceTimeBy(500)
            repeat(2) { f.reportPlatformInterruption(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) }
            assertTrue(original === f.pendingEffectsTarget())
            f.returnMeasuredHeadset()
            runCurrent()
            assertEquals(1, f.rawPlayCalls)
            f.observeAudibleServiceRoute()
            assertTrue(f.player.playWhenReady)
        }
    }

    @Test
    fun `actual focus loss while waiting cancels the short claim`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            startGap(f)
            f.reportPlatformInterruption(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
            f.returnMeasuredHeadset()
            runCurrent()
            assertNull(f.pendingEffectsTarget())
            assertEquals(0, f.rawPlayCalls)
            assertFalse(f.player.playWhenReady)
        }
    }

    private fun startGap(f: PlaybackServiceCommandContinuityFixture) {
        f.installServiceContinuityCallbacks()
        f.startHealthyHeadsetWithoutCar()
        f.removeHeadsetFromInventory()
        assertTrue(f.pauseForSafetyThroughService())
        // The caller's runCurrent starts the asynchronous recovery on the production service scope.
    }
}
