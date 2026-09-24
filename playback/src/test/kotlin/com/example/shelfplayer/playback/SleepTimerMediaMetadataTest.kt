package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class SleepTimerMediaMetadataTest {

    @Test
    fun `active timer appends status to the existing byline without compounding ticks`() {
        val original = item("Ursula K. Le Guin")

        val twelve = assertNotNull(SleepTimerMediaMetadata.project(original, "Sleep 12 min"))
        assertEquals("Ursula K. Le Guin · Sleep 12 min", twelve.mediaMetadata.artist?.toString())

        val eleven = assertNotNull(SleepTimerMediaMetadata.project(twelve, "Sleep 11 min"))
        assertEquals("Ursula K. Le Guin · Sleep 11 min", eleven.mediaMetadata.artist?.toString())
    }

    @Test
    fun `idle projection restores the exact original byline`() {
        val original = item("Octavia E. Butler")
        val active = assertNotNull(SleepTimerMediaMetadata.project(original, "Sleep 9 min"))

        val restored = assertNotNull(SleepTimerMediaMetadata.project(active, null))

        assertEquals("Octavia E. Butler", restored.mediaMetadata.artist?.toString())
        assertNull(SleepTimerMediaMetadata.project(restored, null))
    }

    @Test
    fun `timer is still visible when a book has no author byline`() {
        val active = assertNotNull(SleepTimerMediaMetadata.project(item(null), "Sleep 30 s"))
        assertEquals("Sleep 30 s", active.mediaMetadata.artist?.toString())
    }

    private fun item(artist: String?): MediaItem = MediaItem.Builder()
        .setMediaId("book-a")
        .setUri("https://example.invalid/book.m4b")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("A book")
                .setArtist(artist)
                .build(),
        )
        .build()
}
