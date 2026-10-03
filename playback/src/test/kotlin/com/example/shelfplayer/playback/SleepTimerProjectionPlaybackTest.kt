package com.example.shelfplayer.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the assumption behind the sleep countdown projection (PLAY-008): replacing the current item's metadata
 * once a second must not look like a track change. A transition would reset recovery, cancel car continuity and
 * request a server sync every second (priorities: do not interrupt playback, do not lose progress).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SleepTimerProjectionPlaybackTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `projecting ticking and clearing the countdown does not transition the media item`() {
        val player = ExoPlayer.Builder(context).build()
        try {
            val book = book()
            player.setMediaItem(book)
            player.playWhenReady = true
            shadowOf(Looper.getMainLooper()).idle()

            var transitions = 0
            var playWhenReadyChanges = 0
            player.addListener(
                object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        transitions++
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        playWhenReadyChanges++
                    }
                },
            )

            var current = book
            for (label in listOf<String?>("12:34", "12:33", null)) {
                val replacement = assertNotNull(SleepTimerMediaMetadata.project(current, label))
                player.replaceMediaItem(0, replacement)
                shadowOf(Looper.getMainLooper()).idle()
                current = replacement

                assertEquals(1, player.mediaItemCount)
                assertEquals(0, player.currentMediaItemIndex)
                assertEquals(BOOK_ID, player.currentMediaItem?.mediaId)
                assertTrue(player.playWhenReady, "the countdown must not pause playback")
                // Each replacement must actually reach the player, or zero transitions would prove nothing.
                assertEquals(label ?: "The Tidewatch Cycle", player.currentMediaItem?.mediaMetadata?.title?.toString())
            }

            assertEquals(0, transitions, "metadata replacement must not look like a media-item transition")
            assertEquals(0, playWhenReadyChanges)
            assertEquals("The Tidewatch Cycle", player.currentMediaItem?.mediaMetadata?.title?.toString())
        } finally {
            player.release()
        }
    }

    private fun book() = MediaItem.Builder()
        .setMediaId(BOOK_ID)
        .setUri("https://books.example/api/items/tidewatch/file/1")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("The Tidewatch Cycle")
                .setArtist("Marisol Holt")
                .setIsPlayable(true)
                .build(),
        )
        .build()

    private companion object {
        const val BOOK_ID = "tidewatch"
    }
}
