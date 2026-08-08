package com.example.shelfplayer.domain.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.library.PlayableTrack
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-003 — the arithmetic that turns a book position into a player position.
 *
 * The fixture book is the one `multi-item-play.json` records: six seconds then four, with the second
 * track's `startOffset` reported as `6`. Six-then-four rather than two equal tracks, so a conversion
 * that happened to work by multiplying the index by a duration cannot pass.
 */
class GlobalTimelineTest {

    // --- cursorFor ---------------------------------------------------------------------------------

    @Test
    fun `a position inside the first track resolves to that track`() {
        val cursor = GlobalTimeline.cursorFor(TWO_TRACKS, 2.seconds)

        assertEquals(GlobalTimeline.Cursor(index = 0, offset = 2.seconds), cursor)
    }

    /**
     * The case that only exists on a multi-file book.
     *
     * Seven seconds into a book whose first track is six is one second into the *second* file. Getting
     * this wrong reads as "resuming restarts the chapter", which is what PLAY-003 exists to prevent.
     */
    @Test
    fun `a position past the first track resolves into the second`() {
        val cursor = GlobalTimeline.cursorFor(TWO_TRACKS, 7.seconds)

        assertEquals(GlobalTimeline.Cursor(index = 1, offset = 1.seconds), cursor)
    }

    /** Exactly on a boundary belongs to the track that starts there, not to the one that ends there. */
    @Test
    fun `a position exactly on a track boundary starts the next track`() {
        val cursor = GlobalTimeline.cursorFor(TWO_TRACKS, 6.seconds)

        assertEquals(GlobalTimeline.Cursor(index = 1, offset = Duration.ZERO), cursor)
    }

    /**
     * A stored position past the end of the book clamps rather than pointing past the last track.
     *
     * Reachable in practice: a book whose files were rescanned shorter, or a position written by
     * another client against a different edition.
     */
    @Test
    fun `a position past the end clamps to the end of the last track`() {
        val cursor = GlobalTimeline.cursorFor(TWO_TRACKS, 1.days())

        assertEquals(GlobalTimeline.Cursor(index = 1, offset = 4.seconds), cursor)
    }

    @Test
    fun `a negative position clamps to the start`() {
        val cursor = GlobalTimeline.cursorFor(TWO_TRACKS, (-5).seconds)

        assertEquals(GlobalTimeline.Cursor(index = 0, offset = Duration.ZERO), cursor)
    }

    /** A book with no playable tracks must still produce a cursor rather than throw. */
    @Test
    fun `an empty book resolves to the start`() {
        val cursor = GlobalTimeline.cursorFor(emptyList(), 30.seconds)

        assertEquals(GlobalTimeline.Cursor(index = 0, offset = Duration.ZERO), cursor)
    }

    // --- positionOf --------------------------------------------------------------------------------

    /** The round trip that matters: convert out, convert back, land where you started. */
    @Test
    fun `a cursor converts back to the position it came from`() {
        listOf(0.seconds, 2.seconds, 6.seconds, 7.seconds, 9.seconds).forEach { position ->
            val cursor = GlobalTimeline.cursorFor(TWO_TRACKS, position)

            assertEquals(
                position,
                GlobalTimeline.positionOf(TWO_TRACKS, cursor.index, cursor.offset),
                "round trip through $cursor",
            )
        }
    }

    /**
     * Fractional durations, which is why nothing sums.
     *
     * The server sends milliseconds' worth of precision, and an offset accumulated by adding durations
     * drifts from the server's by a rounding on every track. Here the sum of the first two tracks is
     * `4001ms`, and the server says the third starts at `4000ms` — a summing implementation would put
     * every position in track three one millisecond out, and the error grows with every track.
     */
    @Test
    fun `offsets come from the server rather than from summing durations`() {
        val position = GlobalTimeline.positionOf(RAGGED_TRACKS, index = 2, offset = 500.milliseconds)

        assertEquals(4_500.milliseconds, position)
    }

    @Test
    fun `a track index that does not exist is the start of the book`() {
        assertEquals(Duration.ZERO, GlobalTimeline.positionOf(TWO_TRACKS, index = 9, offset = 3.seconds))
    }

    // --- chapterAt ---------------------------------------------------------------------------------

    @Test
    fun `a position inside a chapter finds it`() {
        assertEquals("The Ebb", GlobalTimeline.chapterAt(CHAPTERS, 3.seconds)?.title)
        assertEquals("The Flood", GlobalTimeline.chapterAt(CHAPTERS, 7.seconds)?.title)
    }

    /**
     * A chapter boundary that is not a *file* boundary.
     *
     * The fixture book's second chapter starts at six seconds, which is also where its second file
     * starts — so the interesting case is asserted with a chapter list that does not line up with the
     * tracks at all. A chapter is found from the position alone, with no track index involved.
     */
    @Test
    fun `chapters are independent of file boundaries`() {
        val midFile = listOf(
            chapter(0, "One", 0.seconds, 3.seconds),
            chapter(1, "Two", 3.seconds, 9.seconds),
        )

        assertEquals("Two", GlobalTimeline.chapterAt(midFile, 4.seconds)?.title)
    }

    /**
     * The very end of a book still has a chapter.
     *
     * The last chapter's `end` and the book's duration can differ by a rounding, and "no chapter" at
     * the moment the listener reaches the end would blank the title just as it matters.
     */
    @Test
    fun `a position past the last chapter stays in the last chapter`() {
        assertEquals("The Flood", GlobalTimeline.chapterAt(CHAPTERS, 30.seconds)?.title)
    }

    @Test
    fun `a position before the first chapter falls back to it`() {
        val late = listOf(chapter(0, "Late", 5.seconds, 9.seconds))

        assertEquals("Late", GlobalTimeline.chapterAt(late, Duration.ZERO)?.title)
    }

    /** PRODUCT_SPEC LIB-004 — a book with no chapters is normal, not an error. */
    @Test
    fun `a book with no chapters has no current chapter`() {
        assertNull(GlobalTimeline.chapterAt(emptyList(), 3.seconds))
    }

    private companion object {
        val BOOK = LibraryItemId("book-1")
        val SERVER = ServerId("server-1")

        /** `multi-item-play.json`: six seconds then four, the second starting at six. */
        val TWO_TRACKS = listOf(
            track(index = 1, startOffset = 0.seconds, duration = 6.seconds),
            track(index = 2, startOffset = 6.seconds, duration = 4.seconds),
        )

        /** Durations that do not sum to the offsets the server reports. */
        val RAGGED_TRACKS = listOf(
            track(index = 1, startOffset = 0.seconds, duration = 2_000.milliseconds),
            track(index = 2, startOffset = 2_000.milliseconds, duration = 2_001.milliseconds),
            track(index = 3, startOffset = 4_000.milliseconds, duration = 2_000.milliseconds),
        )

        val CHAPTERS = listOf(
            chapter(0, "The Ebb", 0.seconds, 6.seconds),
            chapter(1, "The Flood", 6.seconds, 10.seconds),
        )

        fun track(index: Int, startOffset: Duration, duration: Duration) = PlayableTrack(
            index = index,
            url = "https://books.example/api/items/${BOOK.value}/file/$index",
            startOffset = startOffset,
            duration = duration,
            mimeType = "audio/mpeg",
            isExcluded = false,
        )

        fun chapter(index: Int, title: String, start: Duration, end: Duration) = Chapter(
            serverId = SERVER,
            bookId = BOOK,
            index = index,
            title = title,
            start = start,
            end = end,
        )

        fun Int.days(): Duration = (this * 24 * 60 * 60).seconds
    }
}
