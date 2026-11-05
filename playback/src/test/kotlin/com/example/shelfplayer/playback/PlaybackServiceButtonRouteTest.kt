package com.example.shelfplayer.playback

import android.content.Intent
import android.media.AudioDeviceInfo
import android.view.KeyEvent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceButtonRouteTest {
    @Test
    fun `sole headset key reaches service handoff and prepares one muted Play`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, AudioDeviceInfo.TYPE_WIRED_HEADSET).use { f ->
            f.configurePostConnectRace()
            f.service.carConnections.onConnected()
            f.returnHeadset()
            f.selectCar()
            runCurrent()
            f.fromController("com.android.bluetooth") { session, caller ->
                observeKey(f.libraryCallback(), session, caller, KeyEvent.KEYCODE_MEDIA_PLAY)
                assertEquals(
                    OutputIntentOwner.Destination.Car,
                    f.explicitDestination(),
                    "input alone does not transfer",
                )
                assertEquals(0, f.rawPlayCalls)
                assertTrue(transport(f))
                assertEquals(f.endpointId, f.service.audioOutputs.selectedId.value)
                assertTrue(f.commands.play(f.player, stillAuthorized = { true }))
                assertTrue(f.isRouteMuted())
                assertEquals(1, f.rawPlayCalls)
            }
        }
    }

    @Test
    fun `disabling headset inference retains Car and verified dashboard Play still selects Car`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, AudioDeviceInfo.TYPE_WIRED_HEADSET).use { f ->
            f.configurePostConnectRace()
            f.service.carConnections.onConnected()
            f.returnHeadset()
            f.selectCar()
            runCurrent()
            val routing = f.service.playbackCall("getButtonRouting") as ServiceButtonRouting
            routing.settings(PlaybackSettings(headsetButtonRouting = false))
            f.fromController("com.android.bluetooth") { session, caller ->
                observeKey(f.libraryCallback(), session, caller, KeyEvent.KEYCODE_MEDIA_PLAY)
                assertTrue(transport(f))
            }
            assertEquals(OutputIntentOwner.Destination.Car, f.explicitDestination())
            assertTrue(f.service.audioOutputs.handoffs.select(f.endpointId, OutputIntentOwner.Destination.Output))
            f.fromCarController { assertTrue(transport(f)) }
            assertEquals(OutputIntentOwner.Destination.Car, f.explicitDestination())
        }
    }

    @Test
    fun `unverified key or phone Play retains the deliberate Car destination`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, AudioDeviceInfo.TYPE_WIRED_HEADSET).use { f ->
            f.configurePostConnectRace()
            f.service.carConnections.onConnected()
            f.returnHeadset()
            f.selectCar()
            runCurrent()
            f.fromController("com.android.bluetooth", verified = false) { session, caller ->
                observeKey(f.libraryCallback(), session, caller, KeyEvent.KEYCODE_MEDIA_PLAY)
                assertTrue(transport(f))
            }
            assertEquals(OutputIntentOwner.Destination.Car, f.explicitDestination())
            f.fromController("com.android.bluetooth") { session, caller ->
                observeKey(f.libraryCallback(), session, caller, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }
            f.fromController("phone.ui") { _, _ -> assertTrue(transport(f)) }
            assertEquals(OutputIntentOwner.Destination.Car, f.explicitDestination())
            assertEquals(0, f.rawPlayCalls)
        }
    }

    private fun transport(f: PlaybackServiceCommandContinuityFixture): Boolean =
        PlaybackService::class.java.getDeclaredMethod(
            "onControllerTransportRequest",
            Boolean::class.javaPrimitiveType,
            androidx.media3.exoplayer.ExoPlayer::class.java,
        ).apply { isAccessible = true }.invoke(f.service, true, f.player) as Boolean

    private fun observeKey(
        callback: MediaLibrarySession.Callback,
        session: MediaLibrarySession,
        caller: MediaSession.ControllerInfo,
        code: Int,
    ) {
        val intent = Intent(
            Intent.ACTION_MEDIA_BUTTON,
        ).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code))
        assertFalse(callback.onMediaButtonEvent(session, caller, intent))
    }
}
