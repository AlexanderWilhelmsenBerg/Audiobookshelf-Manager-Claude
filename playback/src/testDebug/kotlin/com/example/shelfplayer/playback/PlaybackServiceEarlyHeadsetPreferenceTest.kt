package com.example.shelfplayer.playback

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothProfile
import android.media.AudioManager
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceEarlyHeadsetPreferenceTest {
    @Test
    fun `fresh car link refreshes only the verified playing headset once`() = runTest {
        withPlayingHeadset { fixture ->
            val monitor = fixture.carMonitor()
            val sequence = fixture.explicitSelectionSequence()
            val position = fixture.player.currentPosition

            monitor.connectCar()
            runCurrent()
            monitor.connectCar()
            runCurrent()

            assertEquals(listOf<Int?>(fixture.device.id), fixture.preferredOutputHandles)
            assertTrue(fixture.player.playWhenReady)
            assertEquals(position, fixture.player.currentPosition)
            assertEquals(sequence, fixture.explicitSelectionSequence())
            assertEquals(0, fixture.rawPlayCalls)

            monitor.onHandsFreeChanged(true, BluetoothProfile.STATE_DISCONNECTED, CAR, "car")
            monitor.connectCar()
            runCurrent()
            assertEquals(listOf<Int?>(fixture.device.id, fixture.device.id), fixture.preferredOutputHandles)
        }
    }

    @Test
    fun `route proof lost before the queued effect blocks the refresh`() = runTest {
        withPlayingHeadset { fixture ->
            fixture.carMonitor().connectCar()
            fixture.service.audioOutputs.currentTrackObservation =
                { AudioTrackObservation(2, AudioTrackPath.RemoteSubmix) }
            runCurrent()

            assertTrue(fixture.preferredOutputHandles.isEmpty())
        }
    }

    @Test
    fun `leaving the car link before the queued effect blocks the refresh`() = runTest {
        withPlayingHeadset { fixture ->
            val monitor = fixture.carMonitor()
            monitor.connectCar()
            monitor.onHandsFreeChanged(true, BluetoothProfile.STATE_DISCONNECTED, CAR, "car")
            runCurrent()

            assertTrue(fixture.preferredOutputHandles.isEmpty())
        }
    }

    @Test
    fun `paused playback and calls cannot refresh the preference`() = runTest {
        withPlayingHeadset { fixture ->
            val monitor = fixture.carMonitor()
            fixture.service.audioMode = { AudioManager.MODE_IN_COMMUNICATION }
            monitor.connectCar()
            runCurrent()
            assertTrue(fixture.preferredOutputHandles.isEmpty())

            monitor.onHandsFreeChanged(true, BluetoothProfile.STATE_DISCONNECTED, CAR, "car")
            fixture.service.audioMode = { AudioManager.MODE_NORMAL }
            fixture.reportedIsPlaying = false
            fixture.player.playWhenReady = false
            monitor.connectCar()
            fixture.reportedIsPlaying = true
            fixture.player.playWhenReady = true
            runCurrent()
            assertTrue(fixture.preferredOutputHandles.isEmpty())
        }
    }

    @Test
    fun `a newer car controller connection supersedes the queued refresh`() = runTest {
        withPlayingHeadset { fixture ->
            fixture.carMonitor().connectCar()
            fixture.service.carLifecycle.onControllerConnected()
            runCurrent()

            assertTrue(fixture.preferredOutputHandles.isEmpty())
        }
    }

    private fun TestScope.withPlayingHeadset(test: (PlaybackServiceCommandContinuityFixture) -> Unit) {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { fixture ->
            fixture.service.audioMode = { AudioManager.MODE_NORMAL }
            fixture.service.carLifecycle.onProjectionReported(AndroidAutoProjectionMonitor.State.NotConnected)
            fixture.player.playWhenReady = true
            fixture.reportedIsPlaying = true
            fixture.observeMeasuredAutomaticRoute()
            fixture.returnMeasuredHeadset()
            runCurrent()
            assertEquals(
                listOf<Int?>(fixture.device.id),
                fixture.preferredOutputHandles,
                "ordinary protection is already armed",
            )
            fixture.preferredOutputHandles.clear()
            test(fixture)
        }
    }

    private fun PlaybackServiceCommandContinuityFixture.carMonitor() =
        service.createCarBluetoothMonitor(RuntimeEnvironment.getApplication())

    private fun CarBluetoothArrivalMonitor.connectCar() =
        onHandsFreeChanged(true, BluetoothProfile.STATE_CONNECTED, CAR, "car")

    private companion object {
        const val CAR = BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
    }
}
