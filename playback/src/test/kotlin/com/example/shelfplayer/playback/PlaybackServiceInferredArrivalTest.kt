package com.example.shelfplayer.playback

import android.media.AudioManager
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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

/**
 * PD-009 refinement 2026-11-16 through the real service callbacks and resume effects: Android Auto's focus
 * request inside the car hands-free window resumes the verified headset without waiting for Auto's bind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceInferredArrivalTest {
    @Test
    fun `focus loss inside the car window issues one muted Play after the settle delay`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            start(f, mode = AudioManager.MODE_NORMAL)

            f.interruptUnconfirmedPlayForFocusLoss()
            runCurrent()

            assertTrue(assertNotNull(f.pendingEffectsTarget()).inferredArrival)
            assertEquals(0, f.rawPlayCalls, "the focus burst must pass before Play")
            settle()
            assertEquals(1, f.rawPlayCalls)
            assertTrue(f.isRouteMuted(), "inventory or preference alone cannot unmute")
            f.observeAudibleServiceRoute()
            assertFalse(f.isRouteMuted())
            assertTrue(f.player.playWhenReady)

            f.connectCarSession()
            runCurrent()
            assertEquals(1, f.rawPlayCalls, "the later Auto bind cannot issue a second Play")
        }
    }

    @Test
    fun `a call during the car window stays paused`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            start(f, mode = AudioManager.MODE_RINGTONE)

            f.interruptUnconfirmedPlayForFocusLoss()
            settle()

            assertNull(f.pendingEffectsTarget())
            assertEquals(0, f.rawPlayCalls)
            assertFalse(f.player.playWhenReady)
        }
    }

    @Test
    fun `without a car hands-free link the focus loss keeps waiting`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            start(f, mode = AudioManager.MODE_NORMAL, openWindow = false)

            f.interruptUnconfirmedPlayForFocusLoss()
            settle()

            assertEquals(0, f.rawPlayCalls)
            assertFalse(f.player.playWhenReady)
        }
    }

    @Test
    fun `listener Pause during the settle delay cancels the inferred resume`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            start(f, mode = AudioManager.MODE_NORMAL)
            f.interruptUnconfirmedPlayForFocusLoss()
            runCurrent()
            assertNotNull(f.pendingEffectsTarget())

            assertNull(f.forwarding.handleSetPlayWhenReady(false).get())
            settle()

            assertEquals(0, f.rawPlayCalls)
            assertFalse(f.player.playWhenReady)
        }
    }

    private fun start(f: PlaybackServiceCommandContinuityFixture, mode: Int, openWindow: Boolean = true) {
        f.installServiceContinuityCallbacks()
        f.startHealthyHeadsetWithoutCar()
        f.service.audioMode = { mode }
        if (openWindow) f.service.carBluetoothWindow.onCarHandsFreeConnected("car")
    }

    private fun TestScope.settle() {
        advanceTimeBy(CarContinuityResumeEffects.INFERRED_ARRIVAL_SETTLE.inWholeMilliseconds + 1)
        runCurrent()
    }
}
