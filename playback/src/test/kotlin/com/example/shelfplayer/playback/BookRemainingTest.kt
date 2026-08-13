package com.example.shelfplayer.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.PlayableTrack
import com.example.shelfplayer.core.model.library.PlaybackSession
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-001 — the book's remaining time, as the notification states it.
 *
 * Two things are worth holding to. The first is that the number describes the **book**: Media3's own clocks
 * describe the current media item, and this app's playlist is one item per audio file, so on a six-hour book
 * split into chapters they are off by hours. The second is the rounding — rounding *up* would say
 * "3 h 25 min left" for a book with 3 h 24 m 01 s to go and then sit unchanged for fifty-nine seconds, which
 * reads as a stuck notification. That is the same defect the sleep timer's minutes-only countdown had.
 *
 * Robolectric because the labels are string resources and the item extras are a `Bundle`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookRemainingTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val remaining = BookRemaining()

    /** A six-hour book in two files: one hour, then five. */
    private val items: List<MediaItem> = MediaItems.queueFor(session()).items

    @Test
    fun `there is nothing to say with no player attached`() {
        assertNull(remaining.remaining())
        assertNull(remaining.label(context))
    }

    @Test
    fun `there is nothing to say with no current item`() {
        assertNull(remaining.remainingOf(item = null, positionMs = 0))
    }

    /**
     * A book still being prepared has no duration, and "0 min left" about it is a lie the listener can see.
     *
     * `null` keeps the notification on the author alone until the truth is known.
     */
    @Test
    fun `an unknown duration says nothing rather than zero`() {
        val unknown = MediaItems.queueFor(session(duration = Duration.ZERO)).items.first()

        assertNull(remaining.remainingOf(unknown, positionMs = 0))
        assertNull(remaining.labelFor(context, remaining.remainingOf(unknown, positionMs = 0)))
    }

    /**
     * The position is global, so time already spent in *earlier* files counts.
     *
     * This is the whole reason the class exists. An hour into the second file is two hours into the book, and
     * the player's own numbers would report one.
     */
    @Test
    fun `the remaining time spans the whole book, not the current file`() {
        assertEquals(4.hours, remainingAt(trackIndex = 1, into = 1.hours))
    }

    @Test
    fun `a position in the first file needs no offset`() {
        assertEquals(5.hours + 30.minutes, remainingAt(trackIndex = 0, into = 30.minutes))
    }

    /** An hour and thirty-six minutes into the second file is two hours thirty-six into a six-hour book. */
    @Test
    fun `hours and minutes are shown together`() {
        assertEquals("3 h 24 min left", labelAt(trackIndex = 1, into = 1.hours + 36.minutes))
    }

    @Test
    fun `under an hour drops the hours`() {
        assertEquals("30 min left", labelAt(trackIndex = 1, into = 4.hours + 30.minutes))
    }

    /** Twenty-four minutes and fifty-nine seconds reads as 24, not 25. */
    @Test
    fun `the label rounds down to the minute`() {
        assertEquals("24 min left", labelAt(trackIndex = 1, into = 4.hours + 35.minutes + 1.seconds))
    }

    /** A phrase rather than "0 min left", which reads as finished when it is not. */
    @Test
    fun `the last minute is a phrase`() {
        assertEquals("Less than a minute left", labelAt(trackIndex = 1, into = 4.hours + 59.minutes + 30.seconds))
    }

    /** Past the end — which a server position clamped to the duration can produce — is not a negative. */
    @Test
    fun `a position past the end reports nothing left rather than a negative`() {
        assertEquals(Duration.ZERO, remainingAt(trackIndex = 1, into = 6.hours))
        assertEquals("Less than a minute left", labelAt(trackIndex = 1, into = 6.hours))
    }

    private fun remainingAt(trackIndex: Int, into: Duration): Duration? =
        remaining.remainingOf(items[trackIndex], into.inWholeMilliseconds)

    private fun labelAt(trackIndex: Int, into: Duration): String? =
        remaining.labelFor(context, remainingAt(trackIndex, into))

    private fun session(duration: Duration = 6.hours) = PlaybackSession(
        id = "session-1",
        bookId = LibraryItemId("book-1"),
        title = "The Salt Harbour",
        author = "Marisol Holt",
        coverUrl = null,
        startAt = Duration.ZERO,
        duration = duration,
        tracks = listOf(
            track(index = 0, startOffset = Duration.ZERO, length = 1.hours),
            track(index = 1, startOffset = 1.hours, length = 5.hours),
        ),
        chapters = emptyList(),
    )

    private fun track(index: Int, startOffset: Duration, length: Duration) = PlayableTrack(
        index = index,
        url = "https://books.example/api/items/book-1/file/$index",
        startOffset = startOffset,
        duration = length,
        mimeType = "audio/mpeg",
        isExcluded = false,
    )
}
