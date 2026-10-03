package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerState
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
    fun `active timer replaces primary titles with only the short countdown`() {
        val original = item(
            title = "Canonical title",
            displayTitle = "Display title",
            artist = "Ursula K. Le Guin",
        )

        val twelve = assertNotNull(SleepTimerMediaMetadata.project(original, "12:34"))
        assertEquals("12:34", twelve.mediaMetadata.title?.toString())
        assertEquals("12:34", twelve.mediaMetadata.displayTitle?.toString())
        assertEquals("Ursula K. Le Guin", twelve.mediaMetadata.artist?.toString())

        val eleven = assertNotNull(SleepTimerMediaMetadata.project(twelve, "11:59"))
        assertEquals("11:59", eleven.mediaMetadata.title?.toString())
        assertEquals("11:59", eleven.mediaMetadata.displayTitle?.toString())
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
    fun `ordinary title survives an active countdown projection`() {
        val original = item(title = "Canonical title", displayTitle = "Display title", artist = "Ann Leckie")
        val projected = assertNotNull(SleepTimerMediaMetadata.project(original, "12:34"))

        assertEquals("12:34", projected.mediaMetadata.title?.toString())
        assertEquals("Canonical title", SleepTimerMediaMetadata.ordinaryTitle(projected.mediaMetadata)?.toString())
        assertEquals("Canonical title", SleepTimerMediaMetadata.ordinaryTitle(original.mediaMetadata)?.toString())

        val restored = assertNotNull(SleepTimerMediaMetadata.project(projected, null))
        assertEquals("Canonical title", SleepTimerMediaMetadata.ordinaryTitle(restored.mediaMetadata)?.toString())
    }

    @Test
    fun `ordinary title of an untitled book stays empty while projected`() {
        val projected = assertNotNull(
            SleepTimerMediaMetadata.project(item(title = null, artist = "Anonymous"), "0:30"),
        )

        assertEquals("0:30", projected.mediaMetadata.title?.toString())
        assertNull(SleepTimerMediaMetadata.ordinaryTitle(projected.mediaMetadata))
    }

    @Test
    fun `countdown uses compact clock formatting and rounds a partial second up`() {
        assertEquals("0:00", SleepTimerMediaMetadata.countdownLabel(0.seconds))
        assertEquals("0:42", SleepTimerMediaMetadata.countdownLabel(42.seconds))
        assertEquals("12:34", SleepTimerMediaMetadata.countdownLabel(12.minutes + 34.seconds))
        assertEquals("1:05:06", SleepTimerMediaMetadata.countdownLabel(65.minutes + 6.seconds))
        assertEquals("0:02", SleepTimerMediaMetadata.countdownLabel(1_001.milliseconds))
    }

    @Test
    fun `no countdown label while Android Auto is bound`() {
        val active = activeTimer()

        assertEquals("12:34", SleepTimerMediaMetadata.projectionLabel(active, carBound = false))
        assertNull(SleepTimerMediaMetadata.projectionLabel(active, carBound = true))
        assertNull(SleepTimerMediaMetadata.projectionLabel(SleepTimerState.Idle, carBound = false))
        assertNull(SleepTimerMediaMetadata.projectionLabel(SleepTimerState.Idle, carBound = true))
    }

    @Test
    fun `car connect restores the book title from an active projection and disconnect projects again`() {
        val active = activeTimer()
        val book = item(title = "Canonical title", displayTitle = "Display title", artist = "Ann Leckie")
        val projected = assertNotNull(SleepTimerMediaMetadata.project(book, "12:34"))

        val restored = assertNotNull(
            SleepTimerMediaMetadata.project(projected, SleepTimerMediaMetadata.projectionLabel(active, true)),
        )
        assertEquals("Canonical title", restored.mediaMetadata.title?.toString())
        assertEquals("Display title", restored.mediaMetadata.displayTitle?.toString())
        assertEquals("Ann Leckie", restored.mediaMetadata.artist?.toString())
        assertNull(
            SleepTimerMediaMetadata.project(restored, SleepTimerMediaMetadata.projectionLabel(active, true)),
            "a repeated car-bound publish must not replace the item again",
        )

        val again = assertNotNull(
            SleepTimerMediaMetadata.project(restored, SleepTimerMediaMetadata.projectionLabel(active, false)),
        )
        assertEquals("12:34", again.mediaMetadata.title?.toString())
    }

    private fun activeTimer() = SleepTimerState(
        mode = SleepTimerMode.Fixed(30.minutes),
        remaining = 12.minutes + 34.seconds,
        isFading = false,
    )

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
