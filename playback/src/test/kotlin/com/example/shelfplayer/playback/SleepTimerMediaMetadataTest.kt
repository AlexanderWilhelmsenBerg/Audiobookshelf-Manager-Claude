package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class SleepTimerMediaMetadataTest {

    @Test
    fun `active timer prefixes primary titles without changing the book byline`() {
        val original = item(title = "A book", artist = "Ursula K. Le Guin")

        val twelve = assertNotNull(SleepTimerMediaMetadata.project(original, "12:34"))
        assertEquals("12:34 · A book", twelve.mediaMetadata.title?.toString())
        assertEquals("12:34 · A book", twelve.mediaMetadata.displayTitle?.toString())
        assertEquals("Ursula K. Le Guin", twelve.mediaMetadata.artist?.toString())

        val eleven = assertNotNull(SleepTimerMediaMetadata.project(twelve, "11:59"))
        assertEquals("11:59 · A book", eleven.mediaMetadata.title?.toString())
        assertEquals("11:59 · A book", eleven.mediaMetadata.displayTitle?.toString())
        assertEquals("Ursula K. Le Guin", eleven.mediaMetadata.artist?.toString())
    }

    @Test
    fun `idle projection restores exact original title display title and byline`() {
        val original = item(
            title = "Canonical title",
            displayTitle = "Display title",
            artist = "Octavia E. Butler",
        )
        val active = assertNotNull(SleepTimerMediaMetadata.project(original, "9:00"))

        val restored = assertNotNull(SleepTimerMediaMetadata.project(active, null))

        assertEquals("Canonical title", restored.mediaMetadata.title?.toString())
        assertEquals("Display title", restored.mediaMetadata.displayTitle?.toString())
        assertEquals("Octavia E. Butler", restored.mediaMetadata.artist?.toString())
        assertNull(SleepTimerMediaMetadata.project(restored, null))
    }

    @Test
    fun `timer remains the primary title when a book has no title`() {
        val active = assertNotNull(
            SleepTimerMediaMetadata.project(
                item(title = null, artist = "Anonymous"),
                "0:30",
            ),
        )

        assertEquals("0:30", active.mediaMetadata.title?.toString())
        assertEquals("0:30", active.mediaMetadata.displayTitle?.toString())
        assertEquals("Anonymous", active.mediaMetadata.artist?.toString())
    }

    @Test
    fun `countdown uses compact clock formatting and rounds a partial second up`() {
        assertEquals("0:00", SleepTimerMediaMetadata.countdownLabel(0.seconds))
        assertEquals("0:42", SleepTimerMediaMetadata.countdownLabel(42.seconds))
        assertEquals("12:34", SleepTimerMediaMetadata.countdownLabel(12.minutes + 34.seconds))
        assertEquals("1:05:06", SleepTimerMediaMetadata.countdownLabel(65.minutes + 6.seconds))
        assertEquals("0:02", SleepTimerMediaMetadata.countdownLabel(1_001.milliseconds))
    }

    private fun item(title: String?, displayTitle: String? = null, artist: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId("book-a")
            .setUri("https://example.invalid/book.m4b")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setDisplayTitle(displayTitle)
                    .setArtist(artist)
                    .build(),
            )
            .build()
}
