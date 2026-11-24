package com.example.shelfplayer.playback

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.view.KeyEvent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceInputDiagnosticsTest {
    @Test
    fun `warm callback captures the key before default Media3 handling without moving the route`() = runTest {
        val fixture = PlaybackServiceCommandContinuityFixture(this)
        try {
            fixture.configurePostConnectRace()
            fixture.service.coldStartToggleGate = ColdStartToggleGate()
            val lab = fixture.service.audioRouteLab
            lab.start()
            val before = fixture.currentLock()
            fixture.fromCarController {
                val session = fixture.service.playbackField<MediaLibraryService.MediaLibrarySession>("session")
                val intent = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(
                    Intent.EXTRA_KEY_EVENT,
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY),
                )
                callback(fixture.service).onMediaButtonEvent(
                    session,
                    requireNotNull(session.controllerForCurrentRequest),
                    intent,
                )
            }
            val buttonRecord = lab.state.value.entries.single { "input-key" in it }
            assertContains(buttonRecord, "entry=SessionCallback")
            assertContains(buttonRecord, "caller=AndroidAuto")
            assertContains(buttonRecord, "association=Unproven")
            assertEquals(before, fixture.currentLock())
            lab.stop()
            runCurrent()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `session player request reaches normalized transport evidence with current controller context`() = runTest {
        val fixture = PlaybackServiceCommandContinuityFixture(this)
        try {
            val lab = fixture.service.audioRouteLab
            lab.start()
            fixture.fromCarController {
                fixture.service.createSessionPlayer(fixture.player).handleSetPlayWhenReady(false)
            }
            val request = lab.state.value.entries.single { "input-transport" in it }
            assertContains(request, "requested=Pause")
            assertContains(request, "caller=AndroidAuto")
            assertContains(request, "association=Unproven")
            lab.stop()
            runCurrent()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `only the matching notification relay inherits the verified car Pause key`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { f ->
            f.configurePostConnectRace()
            f.service.coldStartToggleGate = ColdStartToggleGate()
            val lab = f.service.audioRouteLab
            lab.start()
            f.fromController("com.google.android.projection.gearhead") { session, car ->
                val packageName = RuntimeEnvironment.getApplication().packageName
                val relay = controller(packageName, notification = true)
                val app = controller(packageName, notification = false)
                assertTrue(session.isMediaNotificationController(relay))
                val intent = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(
                    Intent.EXTRA_KEY_EVENT,
                    KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0),
                )
                val callbacks = f.libraryCallback()
                fun observeKey() = assertFalse(callbacks.onMediaButtonEvent(session, car, intent))
                fun pause(caller: MediaSession.ControllerInfo) {
                    f.setRequestController(session, caller)
                    assertNull(f.forwarding.handleSetPlayWhenReady(false).get())
                }

                f.player.playWhenReady = true
                observeKey()
                pause(relay)
                assertFalse(f.player.playWhenReady, "attribution must preserve Pause handling")
                pause(relay)
                observeKey()
                pause(app)
                pause(relay)
                observeKey()
                shadowOf(Looper.getMainLooper()).idle()
                pause(relay)

                val requests = lab.state.value.entries.filter { "input-transport" in it }
                assertEquals(5, requests.size)
                assertContains(requests.first(), "caller=SameApp")
                assertContains(requests.first(), "relayedKeyCaller=AndroidAuto")
                assertTrue(requests.drop(1).none { "relayedKeyCaller=AndroidAuto" in it })
            }
            lab.stop()
            runCurrent()
        }
    }

    private fun controller(packageName: String, notification: Boolean): MediaSession.ControllerInfo =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            packageName,
            Process.myPid(),
            Process.myUid(),
            1,
            1,
            true,
            Bundle().apply { putBoolean(MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, notification) },
            true,
        )

    private fun callback(service: PlaybackService): MediaLibraryService.MediaLibrarySession.Callback {
        val type = PlaybackService::class.java.declaredClasses.single { it.simpleName == "LibraryCallback" }
        return type.getDeclaredConstructor(PlaybackService::class.java).apply { isAccessible = true }
            .newInstance(service) as MediaLibraryService.MediaLibrarySession.Callback
    }
}
