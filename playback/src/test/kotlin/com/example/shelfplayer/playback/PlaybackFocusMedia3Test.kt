package com.example.shelfplayer.playback

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.testing.TestAppClock
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAudioManager
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The real pinned Media3 focus manager supplies events; the observer cannot request focus. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackFocusMedia3Test {
    @Test
    fun `speech duck request is observed as suppression and gain resumes through Media3 alone`() {
        Fixture().use { f ->
            f.start()
            val request = f.audio.lastAudioFocusRequest
            f.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            f.await { f.has("transient-suppression") }
            assertFalse(f.player.isPlaying)
            assertTrue(f.player.playWhenReady)
            f.focus(AudioManager.AUDIOFOCUS_GAIN)
            f.await { f.has("playback-active") }
            assertTrue(f.player.isPlaying)
            assertSame(request, f.audio.lastAudioFocusRequest, "The diagnostic must not request focus")
            assertTrue(f.records.all { it.public("androidFocusChange") == "unavailable" })
        }
    }

    @Test
    fun `permanent platform loss stays paused and is labelled loss or denial without guessing requester`() {
        Fixture().use { f ->
            f.start()
            f.focus(AudioManager.AUDIOFOCUS_LOSS)
            f.await { f.has("loss-or-denial") }
            assertFalse(f.player.playWhenReady)
            assertFalse(f.player.isPlaying)
            assertEquals("unknown", f.records.last().public("focusRequester"))
            assertFalse(f.has("playback-active"))
        }
    }

    @Test
    fun `denied initial focus request is not misreported as a permanent platform callback`() {
        Fixture().use { f ->
            f.audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            f.prepare()
            f.await { f.has("loss-or-denial") }
            assertFalse(f.player.playWhenReady)
            assertEquals("unavailable", f.records.last().public("androidFocusChange"))
        }
    }

    private class Fixture : AutoCloseable {
        private val context = RuntimeEnvironment.getApplication()
        val audio: ShadowAudioManager = shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        val records = mutableListOf<List<LogField>>()
        val player = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true,
            ).build().apply { addListener(PlaybackFocusDiagnostics(TestAppClock(), records::add)) }

        fun prepare() {
            player.setMediaSource(SilenceMediaSource.Factory().setDurationUs(60_000_000).createMediaSource())
            player.prepare()
            player.play()
        }

        fun start() {
            prepare()
            await { player.isPlaying && audio.lastAudioFocusRequest != null }
        }

        fun focus(change: Int) {
            val listener = requireNotNull(audio.lastAudioFocusRequest.listener)
            Handler(player.playbackLooper).post { listener.onAudioFocusChange(change) }
        }

        fun has(observation: String) = records.any { it.public("focusObservation") == observation }

        fun await(predicate: () -> Boolean) {
            repeat(5_000) {
                if (predicate()) return
                shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
                Thread.sleep(1)
            }
            assertTrue(predicate(), "Media3 focus event did not arrive")
        }

        override fun close() = player.release()
    }

    private companion object {
        fun List<LogField>.public(key: String) = filterIsInstance<LogField.Public>().single { it.key == key }.value
    }
}
