package com.example.shelfplayer.domain.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Chapter
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-003 — finding and navigating chapters on a book's timeline.
 *
 * The fixture book is the one `multi-item-play.json` records: ten seconds, split into a six-second
 * chapter and a four-second one. Since ADR-0016 the player's timeline *is* this timeline, so nothing
 * here converts anything — the tests that did have gone with the arithmetic.
 */
class GlobalTimelineTest {

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

    // --- Chapter navigation ------------------------------------------------------------------------

    @Test
    fun `next chapter goes to the following boundary`() {
        assertEquals(6.seconds, GlobalTimeline.nextChapterStart(CHAPTERS, 2.seconds))
    }

    /** Nowhere to go rather than wrapping to the start, which would be a surprise and not a feature. */
    @Test
    fun `next chapter in the last chapter has nowhere to go`() {
        assertNull(GlobalTimeline.nextChapterStart(CHAPTERS, 8.seconds))
    }

    /**
     * The behaviour every media player has, and the reason it is not simply "go back one".
     *
     * Well into a chapter, "previous" restarts *this* chapter. That is what a listener means when they
     * press it having missed something.
     */
    @Test
    fun `previous chapter restarts the current one when well into it`() {
        // The window is passed explicitly rather than left to the default: the fixture book's chapters
        // are seconds long, so relying on the real three-second window would make this test a statement
        // about the fixture's proportions instead of about the behaviour.
        assertEquals(
            6.seconds,
            GlobalTimeline.previousChapterStart(CHAPTERS, 9.seconds, restartWithin = 1.seconds),
        )
    }

    /**
     * ...and just after a boundary it goes back one, so two presses move two chapters.
     *
     * Without the window, a second press would stick on the boundary it had just moved to.
     */
    @Test
    fun `previous chapter goes back one when just past a boundary`() {
        assertEquals(
            Duration.ZERO,
            GlobalTimeline.previousChapterStart(CHAPTERS, 7.seconds, restartWithin = 5.seconds),
        )
    }

    @Test
    fun `previous chapter at the very start of the book has nowhere to go`() {
        assertNull(GlobalTimeline.previousChapterStart(CHAPTERS, Duration.ZERO))
    }

    /** Inside the first chapter it restarts it, rather than reporting nowhere to go. */
    @Test
    fun `previous chapter inside the first chapter restarts the book`() {
        assertEquals(
            Duration.ZERO,
            GlobalTimeline.previousChapterStart(CHAPTERS, 4.seconds, restartWithin = 1.seconds),
        )
    }

    /** Navigation is by time, so a server that sends chapters unordered cannot change the answer. */
    @Test
    fun `navigation reads chapters in time order rather than list order`() {
        val shuffled = CHAPTERS.reversed()

        assertEquals(
            GlobalTimeline.nextChapterStart(CHAPTERS, 2.seconds),
            GlobalTimeline.nextChapterStart(shuffled, 2.seconds),
        )
        assertEquals(
            GlobalTimeline.previousChapterStart(CHAPTERS, 9.seconds, restartWithin = 1.seconds),
            GlobalTimeline.previousChapterStart(shuffled, 9.seconds, restartWithin = 1.seconds),
        )
    }

    @Test
    fun `the chapter index follows the position`() {
        assertEquals(0, GlobalTimeline.chapterIndexAt(CHAPTERS, 2.seconds))
        assertEquals(1, GlobalTimeline.chapterIndexAt(CHAPTERS, 7.seconds))
        assertNull(GlobalTimeline.chapterIndexAt(emptyList(), 7.seconds))
    }

    @Test
    fun `a book with no chapters cannot be navigated by chapter`() {
        assertNull(GlobalTimeline.nextChapterStart(emptyList(), 3.seconds))
        assertNull(GlobalTimeline.previousChapterStart(emptyList(), 3.seconds))
    }

    private companion object {
        val BOOK = LibraryItemId("book-1")
        val SERVER = ServerId("server-1")

        val CHAPTERS = listOf(
            chapter(0, "The Ebb", 0.seconds, 6.seconds),
            chapter(1, "The Flood", 6.seconds, 10.seconds),
        )

        fun chapter(index: Int, title: String, start: Duration, end: Duration) = Chapter(
            serverId = SERVER,
            bookId = BOOK,
            index = index,
            title = title,
            start = start,
            end = end,
        )
    }
}
