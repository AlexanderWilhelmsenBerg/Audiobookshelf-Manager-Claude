package com.example.shelfplayer.playback

import android.content.Intent
import android.view.KeyEvent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContains
import kotlin.test.assertEquals

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
            val key = lab.state.value.entries.single { "input-key" in it }
            assertContains(key, "entry=SessionCallback")
            assertContains(key, "caller=AndroidAuto")
            assertContains(key, "association=Unproven")
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

    private fun callback(service: PlaybackService): MediaLibraryService.MediaLibrarySession.Callback {
        val type = PlaybackService::class.java.declaredClasses.single { it.simpleName == "LibraryCallback" }
        return type.getDeclaredConstructor(PlaybackService::class.java).apply { isAccessible = true }
            .newInstance(service) as MediaLibraryService.MediaLibrarySession.Callback
    }
}
