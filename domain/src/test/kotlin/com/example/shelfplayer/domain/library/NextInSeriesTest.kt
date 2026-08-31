package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.domain.TEST_INSTANT
import com.example.shelfplayer.domain.book
import com.example.shelfplayer.domain.membership
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PRODUCT_SPEC 6.4 step 6 / DL-005 — the one answer to *"what comes after this book"*.
 *
 * Both the smart downloader and auto-advance read this, so a wrong answer here is a book downloaded that
 * is not the book played. That is why it is a pure function with its own tests rather than two inlined
 * loops that were free to disagree — which is what it replaced.
 */
class NextInSeriesTest {

    /** The classic audiobook-series bug: string ordering puts 10 before 2, and a listener notices. */
    @Test
    fun `the order is numeric, not alphabetical`() {
        val books = listOf(
            book("b2", sequence = "2"),
            book("b10", sequence = "10"),
            book("b1", sequence = "1"),
        )

        val order = booksInSeriesOrder(books, books.first().seriesMemberships.first()).map { it.id.value }

        assertEquals(listOf("b1", "b2", "b10"), order)
    }

    /** The plain case the owner asked for: book 3 ends, book 4 starts. */
    @Test
    fun `the next book is the one after this one`() {
        val books = listOf(book("b3", sequence = "3"), book("b4", sequence = "4"), book("b5", sequence = "5"))

        assertEquals("b4", nextInSeries(books, LibraryItemId("b3"), skipFinished = false)?.id?.value)
    }

    /**
     * Auto-advance walks past finished books; smart download does not.
     *
     * Playing a finished book would resume it at its own end, reach `STATE_ENDED` immediately and advance
     * again — a run of finished books would flick through itself and land somewhere arbitrary.
     */
    @Test
    fun `skipping finished books changes the answer`() {
        val books = listOf(
            book("b1", sequence = "1"),
            // `playedAt` as well: the fixture only builds a progress row when there is a listen behind it.
            book("b2", sequence = "2", playedAt = TEST_INSTANT, isFinished = true),
            book("b3", sequence = "3"),
        )

        assertEquals("b2", nextInSeries(books, LibraryItemId("b1"), skipFinished = false)?.id?.value)
        assertEquals("b3", nextInSeries(books, LibraryItemId("b1"), skipFinished = true)?.id?.value)
    }

    /** The last book of a series, a book in no series, and a book not in the list are one answer: nothing. */
    @Test
    fun `there is no next book when there is no next book`() {
        val books = listOf(book("b1", sequence = "1"), book("b2", sequence = "2"), book("solo"))

        assertNull(nextInSeries(books, LibraryItemId("b2"), skipFinished = true))
        assertNull(nextInSeries(books, LibraryItemId("solo"), skipFinished = true))
        assertNull(nextInSeries(books, LibraryItemId("never-heard-of-it"), skipFinished = true))
    }

    /**
     * A book in two series follows the one the **server** marked primary.
     *
     * Book 1 of the omnibus is book 3 of the trilogy, and the two have different next books. Without the
     * primary flag the answer would depend on which membership happened to be listed first, which is a
     * property of a sync rather than a decision anybody made.
     */
    @Test
    fun `a book in two series follows its primary one`() {
        val books = listOf(
            book(
                "b3",
                memberships = listOf(
                    membership(seriesId = "omnibus", name = "Collected Holt", sequence = "1", isPrimary = false),
                    membership(seriesId = "trilogy", name = "The Salt Cycle", sequence = "3", isPrimary = true),
                ),
            ),
            book(
                "t4",
                memberships = listOf(membership(seriesId = "trilogy", name = "The Salt Cycle", sequence = "4")),
            ),
            book(
                "o2",
                memberships = listOf(membership(seriesId = "omnibus", name = "Collected Holt", sequence = "2")),
            ),
        )

        assertEquals("t4", nextInSeries(books, LibraryItemId("b3"), skipFinished = true)?.id?.value)
    }

    /**
     * Two books sharing a sequence resolve by title and then id, so the answer never depends on list order.
     *
     * Self-hosted libraries do contain this: two files tagged `2` because a novella was numbered alongside
     * the novel. The previous inline sort ordered by sequence alone, so which of them was "next" came out
     * of whatever order the query happened to return.
     */
    @Test
    fun `a tied sequence still has one answer`() {
        val first = listOf(
            book("bz", title = "Zebra", sequence = "2"),
            book("ba", title = "Apple", sequence = "2"),
            book("b1", title = "One", sequence = "1"),
        )
        val reversed = first.reversed()

        assertEquals(
            nextInSeries(first, LibraryItemId("b1"), skipFinished = false)?.id?.value,
            nextInSeries(reversed, LibraryItemId("b1"), skipFinished = false)?.id?.value,
        )
        assertEquals("ba", nextInSeries(first, LibraryItemId("b1"), skipFinished = false)?.id?.value)
    }
}
