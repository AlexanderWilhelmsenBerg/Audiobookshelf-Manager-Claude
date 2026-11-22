package com.example.shelfplayer.playback

import android.media.AudioManager
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualPlaybackFocusTest {
    @Test
    fun `loss is logged without a new request and the next explicit Play can request again`() {
        val audio = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        val platform = shadowOf(audio)
        val events = mutableListOf<LogEvent>()
        val focus = ManualPlaybackFocus(
            audio,
            object : Logger {
                override fun log(event: LogEvent) {
                    events += event
                }
            },
        )

        assertFalse(focus.play(explicit = false))
        assertNull(platform.lastAudioFocusRequest)
        assertTrue(focus.play(explicit = true))
        val request = platform.lastAudioFocusRequest.audioFocusRequest
        val callback = ReflectionHelpers.callInstanceMethod<AudioManager.OnAudioFocusChangeListener>(
            request,
            "getOnAudioFocusChangeListener",
        )
        callback.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertEquals(listOf("Manual audio focus changed"), events.map(LogEvent::message))
        assertTrue(focus.play(explicit = false))
        assertSame(request, platform.lastAudioFocusRequest.audioFocusRequest)

        focus.release()
        assertSame(request, platform.lastAbandonedAudioFocusRequest)
        callback.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1, events.size)
        assertFalse(focus.play(explicit = false))
        assertTrue(focus.play(explicit = true))
        focus.release()
    }

    @Test
    fun `Android denial does not enable a start or authorize later automatic Play`() {
        val audio = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        val platform = shadowOf(audio)
        val focus = ManualPlaybackFocus(
            audio,
            object : Logger {
                override fun log(event: LogEvent) = Unit
            },
        )
        platform.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertFalse(focus.play(explicit = true))
        assertFalse(focus.play(explicit = false))
    }
}
