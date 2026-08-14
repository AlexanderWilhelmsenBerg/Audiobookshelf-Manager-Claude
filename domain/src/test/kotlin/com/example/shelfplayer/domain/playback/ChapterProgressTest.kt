package com.example.shelfplayer.domain.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Chapter
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** PRODUCT_SPEC PLAY-003 — the chapter bar's arithmetic, including every way it could render nonsense. */
class ChapterProgressTest {

    @Test
    fun `a position inside a chapter reports how far through it is`() {
        val progress = ChapterProgress.at(CHAPTERS, 15.minutes)

        assertEquals("Two", progress?.chapter?.title)
        assertEquals(5.minutes, progress?.elapsed)
        assertEquals(15.minutes, progress?.remaining)
        assertEquals(0.25f, progress?.fraction)
    }

    /** What "Chapter 3 of 24" is built from. Time order, not the server's `index`. */
    @Test
    fun `the ordinal counts from one in time order`() {
        val shuffled = CHAPTERS.reversed()

        val progress = ChapterProgress.at(shuffled, 15.minutes)

        assertEquals(2, progress?.ordinal)
        assertEquals(3, progress?.count)
    }

    @Test
    fun `the very start of a chapter is zero, not one`() {
        val progress = ChapterProgress.at(CHAPTERS, 10.minutes)

        assertEquals("Two", progress?.chapter?.title)
        assertEquals(Duration.ZERO, progress?.elapsed)
        assertEquals(0f, progress?.fraction)
    }

    /**
     * Past the last chapter's end, which is reachable on any book.
     *
     * A final chapter's `end` and the book's duration routinely differ by a rounding, so the last seconds
     * of a book sit outside every chapter. Without the clamp the bar overfills and the remaining time
     * counts backwards.
     */
    @Test
    fun `a position past the last chapter fills the bar rather than overflowing it`() {
        val progress = ChapterProgress.at(CHAPTERS, 2.hours())

        assertEquals("Three", progress?.chapter?.title)
        assertEquals(1f, progress?.fraction)
        assertEquals(Duration.ZERO, progress?.remaining)
    }

    /** Before the first chapter starts — a book whose metadata begins after an intro. */
    @Test
    fun `a position before the first chapter reads as its start`() {
        val late = listOf(chapter(0, "Late", 30.seconds, 90.seconds))

        val progress = ChapterProgress.at(late, Duration.ZERO)

        assertEquals(Duration.ZERO, progress?.elapsed)
        assertEquals(60.seconds, progress?.remaining)
    }

    /** A zero-length chapter is a division by zero waiting to happen. */
    @Test
    fun `a chapter with no length reports no progress rather than dividing by zero`() {
        val empty = listOf(chapter(0, "Empty", 0.seconds, 0.seconds))

        val progress = ChapterProgress.at(empty, 5.seconds)

        assertEquals(0f, progress?.fraction)
        assertEquals(Duration.ZERO, progress?.remaining)
    }

    /**
     * A book with no chapter metadata, which is common in a self-hosted library.
     *
     * `null` rather than an empty snapshot, so the caller can leave the bar out entirely instead of
     * drawing one that will never move.
     */
    @Test
    fun `a book with no chapters has no chapter progress`() {
        assertNull(ChapterProgress.at(emptyList(), 5.minutes))
    }

    private companion object {
        val BOOK = LibraryItemId("book-1")
        val SERVER = ServerId("server-1")

        val CHAPTERS = listOf(
            chapter(0, "One", 0.minutes, 10.minutes),
            chapter(1, "Two", 10.minutes, 30.minutes),
            chapter(2, "Three", 30.minutes, 45.minutes),
        )

        fun chapter(index: Int, title: String, start: Duration, end: Duration) = Chapter(
            serverId = SERVER,
            bookId = BOOK,
            index = index,
            title = title,
            start = start,
            end = end,
        )

        fun Int.hours(): Duration = (this * 60).minutes
    }
}
