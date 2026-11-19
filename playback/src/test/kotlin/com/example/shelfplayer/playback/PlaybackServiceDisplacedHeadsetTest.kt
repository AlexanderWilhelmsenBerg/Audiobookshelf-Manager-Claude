package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.shelfplayer.core.model.playback.AudioOutputActivitySource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * APK 2272 event order through the real router retirement and service callbacks: the car's own Bluetooth
 * takes over media audio, the playing track is measured on the phone speaker once, the headset endpoint
 * leaves the inventory while the provider still reports NotConnected, then focus is lost and Auto binds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceDisplacedHeadsetTest {
    @Test
    fun `speaker sample before endpoint removal keeps recovery for the first car bind`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            f.installServiceContinuityCallbacks()
            f.startHealthyHeadsetWithoutCar()
            val recovery = f.service.playbackField<CarContinuityRecoveryCoordinator>("carRecovery")
            // The shared fixture's constructor already consumed one arrival on this gate; start a fresh drive.
            CarArrivalResumeGate::class.java.getDeclaredField("arrivalRecorded").apply { isAccessible = true }
                .setBoolean(recovery.gate, false)
            f.service.carLifecycle.onProjectionReported(AndroidAutoProjectionMonitor.State.NotConnected)

            measureSpeakerWhilePlaying(f)
            removeEndpointWhilePlaying(f)

            assertFalse(f.player.playWhenReady, "the unverified route must still be paused")
            assertEquals(0L, f.explicitSelectionSequence(), "internal cleanup must not look like a listener choice")
            assertEquals(
                f.endpointId,
                f.service.audioOutputs.selectedId.value,
                "the verified headset stays the owned destination",
            )
            assertEquals(CarArrivalResumeGate.Phase.HeadsetReturn, assertNotNull(f.pendingEffectsTarget()).phase)

            f.advanceElapsedClock(1600.milliseconds)
            f.reportPlatformInterruption(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
            runCurrent()
            f.advanceElapsedClock(5200.milliseconds)
            f.connectCarSession()
            val arrival = recovery.carArrival(
                f.service.clock.elapsed(),
                f.service.routeOwnership.currentGeneration,
                f.explicitSelectionSequence(),
            )

            assertEquals(CarArrivalResumeGate.Status.Ready, arrival.status)
            assertEquals(f.endpointId, arrival.target?.outputId)
            assertEquals(30.seconds, arrival.target?.deadline, "the deadline is the original interruption's")
            assertEquals(0, f.rawPlayCalls, "authorization alone does not issue Play")
        }
    }

    /** The service collector's body for one publication that measured the playing track on the phone speaker. */
    private fun measureSpeakerWhilePlaying(f: PlaybackServiceCommandContinuityFixture) {
        val source = AudioOutputActivitySource.PlayingAudioTrack
        val headset = f.service.audioOutputs.outputs.value.single { it.id == f.endpointId }
        val rows = listOf(
            requireNotNull(OutputDevices.outputOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, null))
                .copy(isActive = true, activitySource = source),
            headset.copy(isActive = false, activitySource = source),
        )
        f.service.routeOwnership.onOutputsChanged(rows, isPlaying = true)
        f.service.playbackCall("observeCarContinuityHeadset", List::class.java, rows)
    }

    /** Android removes the endpoint while the book is still audible; the real router refresh handles it. */
    private fun TestScope.removeEndpointWhilePlaying(f: PlaybackServiceCommandContinuityFixture) {
        val context = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(context.getSystemService(AudioManager::class.java)).setOutputDevices(emptyList())
        f.service.audioOutputs.setPlaybackField("currentTrackObservation", { null })
        f.service.audioOutputs.setPlaybackField("currentTrackRoutes", { null })
        f.service.audioOutputs.playbackCall("refresh")
        runCurrent()
        f.setPlaybackField("reportedIsPlaying", f.player.playWhenReady)
    }
}
