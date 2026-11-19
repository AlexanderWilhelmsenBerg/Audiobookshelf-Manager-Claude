package com.example.shelfplayer.playback

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
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
    fun `fresh exact headset prediction can start one muted probe before fallback`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            start(f, mode = AudioManager.MODE_NORMAL)
            beginEarlyObservation(f)

            advanceTimeBy(149)
            runCurrent()
            assertEquals(0, f.rawPlayCalls, "preserve a minimum focus settle time")
            advanceTimeBy(1)
            runCurrent()

            assertEquals(1, f.rawPlayCalls, "safe evidence should beat the 500 ms fallback")
            assertTrue(f.isRouteMuted(), "policy prediction cannot unmute")
            f.returnMeasuredHeadset()
            f.observeAudibleServiceRoute()
            settle()
            assertEquals(1, f.rawPlayCalls)
        }
    }

    @Test
    fun `car track after early muted Play fails closed`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            start(f, mode = AudioManager.MODE_NORMAL)
            beginEarlyObservation(f)
            advanceTimeBy(150)
            runCurrent()
            assertEquals(1, f.rawPlayCalls)
            assertTrue(f.isRouteMuted())

            f.reportPlayingOnCarDuringProbe()
            runCurrent()
            settle()

            assertFalse(f.player.playWhenReady)
            assertEquals(1, f.rawPlayCalls, "a wrong route cannot spend a second automatic Play")
        }
    }

    @Test
    fun `framework route paired with an unmapped car sink keeps the conservative wait`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            start(f, mode = AudioManager.MODE_NORMAL)
            beginEarlyObservation(f, withUnmappedCar = true)
            advanceTimeBy(299)
            runCurrent()
            assertEquals(0, f.rawPlayCalls)

            f.publishPolicyHeadsetRoute()
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, f.rawPlayCalls)
            assertTrue(f.isRouteMuted())
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
    fun `ringing after the qualifying route publication blocks recovery`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            start(f, mode = AudioManager.MODE_NORMAL)
            beginEarlyObservation(f)
            f.service.audioMode = { AudioManager.MODE_RINGTONE }

            settle()
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
            beginEarlyObservation(f)
            assertNotNull(f.pendingEffectsTarget())

            assertNull(f.forwarding.handleSetPlayWhenReady(false).get())
            settle()

            assertEquals(0, f.rawPlayCalls)
            assertFalse(f.player.playWhenReady)
        }
    }

    private fun TestScope.beginEarlyObservation(
        fixture: PlaybackServiceCommandContinuityFixture,
        withUnmappedCar: Boolean = false,
    ) {
        fixture.interruptUnconfirmedPlayForFocusLoss()
        runCurrent()
        advanceTimeBy(200)
        fixture.publishPolicyHeadsetRoute(withUnmappedCar)
        runCurrent()
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

internal fun PlaybackServiceCommandContinuityFixture.publishPolicyHeadsetRoute(withUnmappedCar: Boolean = false) {
    reportedIsPlaying = false
    val router = service.audioOutputs
    router.currentTrackObservation = { null }
    router.setPlaybackField("currentTrackRoutes", { null })
    val routes = if (withUnmappedCar) {
        ImmutableList.of(device, remoteSubmix())
    } else {
        ImmutableList.of(device)
    }
    val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    shadowOf(RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java))
        .setAudioDevicesForAttributes(attributes, routes)
    router.onActualTrackRouteChanged()
}

private fun remoteSubmix(): AudioDeviceInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_REMOTE_SUBMIX).build()
} else {
    error("The remote-mix fixture requires Android 12 or later")
}

internal fun PlaybackServiceCommandContinuityFixture.reportPlayingOnCarDuringProbe() {
    reportedIsPlaying = true
    val router = service.audioOutputs
    router.currentTrackObservation = { AudioTrackObservation(1, AudioTrackPath.RemoteSubmix) }
    router.setPlaybackField("currentTrackRoutes", { setOf("car:fixture") })
    router.onActualTrackRouteChanged()
}
