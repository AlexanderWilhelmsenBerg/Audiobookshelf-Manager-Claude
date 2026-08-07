package com.example.shelfplayer.domain.library

import com.example.shelfplayer.domain.TEST_INSTANT
import com.example.shelfplayer.domain.book
import com.example.shelfplayer.domain.membership
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PRODUCT_SPEC LIB-003 / TC-16 — grouping a library's books into series. */
class SeriesShelfTest {

    /**
     * The bug this whole type exists to prevent: `10` sorting before `2`.
     *
     * Server sequences are free text, so a list ordered by the raw string reads 1, 10, 11, 2 — which is
     * the single most visible way a series browser can be wrong.
     */
    @Test
    fun `books are ordered by sequence numerically, not lexicographically`() {
        val shelves = groupIntoSeries(
            listOf(
                book(id = "b10", title = "Tenth", sequence = "10"),
                book(id = "b2", title = "Second", sequence = "2"),
                book(id = "b1", title = "First", sequence = "1"),
            ),
        )

        assertEquals(listOf("First", "Second", "Tenth"), shelves.single().books.map { it.title })
    }

    /** PRODUCT_SPEC LIB-003 — `2.5` is a real position in an audiobook series, not a rounding error. */
    @Test
    fun `a decimal sequence sits between the two whole numbers`() {
        val shelves = groupIntoSeries(
            listOf(
                book(id = "b3", title = "Third", sequence = "3"),
                book(id = "b25", title = "Novella", sequence = "2.5"),
                book(id = "b2", title = "Second", sequence = "2"),
            ),
        )

        assertEquals(listOf("Second", "Novella", "Third"), shelves.single().books.map { it.title })
    }

    /** Non-numeric sequences sort after every numbered book rather than being dropped or leading. */
    @Test
    fun `a named sequence sorts after the numbered ones`() {
        val shelves = groupIntoSeries(
            listOf(
                book(id = "bp", title = "Prequel", sequence = "Prequel"),
                book(id = "b1", title = "First", sequence = "1"),
            ),
        )

        assertEquals(listOf("First", "Prequel"), shelves.single().books.map { it.title })
    }

    /**
     * PRODUCT_SPEC LIB-003 — a book in two series appears under both, at each series' own position.
     *
     * This is the case `primarySequence()` cannot answer: it returns one number, and the omnibus is
     * book 3 of one series and book 1 of the other.
     */
    @Test
    fun `a book in two series appears in both, at the position each one gives it`() {
        val omnibus = book(
            id = "omnibus",
            title = "Omnibus",
            memberships = listOf(
                membership(seriesId = "voyage", name = "The Long Voyage", sequence = "3"),
                membership(seriesId = "collected", name = "Collected Editions", sequence = "1", isPrimary = false),
            ),
        )
        val first = book(
            id = "first",
            title = "First",
            memberships = listOf(membership(seriesId = "voyage", name = "The Long Voyage", sequence = "1")),
        )
        val later = book(
            id = "later",
            title = "Later Collection",
            memberships = listOf(
                membership(seriesId = "collected", name = "Collected Editions", sequence = "2"),
            ),
        )

        val shelves = groupIntoSeries(listOf(omnibus, first, later))

        assertEquals(listOf("Collected Editions", "The Long Voyage"), shelves.map { it.series.name })
        assertEquals(listOf("Omnibus", "Later Collection"), shelves[0].books.map { it.title })
        assertEquals(listOf("First", "Omnibus"), shelves[1].books.map { it.title })
    }

    /** A book in no series is not in a series. It belongs to a different browse axis, not a bucket here. */
    @Test
    fun `books with no membership produce no series`() {
        assertTrue(groupIntoSeries(listOf(book(id = "b1"), book(id = "b2"))).isEmpty())
    }

    /** The list of series is alphabetical and stable, so two renders of one library never disagree. */
    @Test
    fun `series are ordered by name, case-insensitively`() {
        val shelves = groupIntoSeries(
            listOf(
                book(id = "z", memberships = listOf(membership(seriesId = "z", name = "zenith"))),
                book(id = "a", memberships = listOf(membership(seriesId = "a", name = "Anchor"))),
                book(id = "m", memberships = listOf(membership(seriesId = "m", name = "Meridian"))),
            ),
        )

        assertEquals(listOf("Anchor", "Meridian", "zenith"), shelves.map { it.series.name })
    }

    /** Two books with the same sequence still render in one fixed order — title, then id. */
    @Test
    fun `books sharing a sequence fall back to title order`() {
        val shelves = groupIntoSeries(
            listOf(
                book(id = "b", title = "Beta", sequence = "1"),
                book(id = "a", title = "Alpha", sequence = "1"),
            ),
        )

        assertEquals(listOf("Alpha", "Beta"), shelves.single().books.map { it.title })
    }

    @Test
    fun `next up is the first book that is not finished`() {
        val shelf = groupIntoSeries(
            listOf(
                book(id = "b1", title = "First", sequence = "1", playedAt = TEST_INSTANT, isFinished = true),
                book(id = "b2", title = "Second", sequence = "2"),
                book(id = "b3", title = "Third", sequence = "3"),
            ),
        ).single()

        assertEquals(3, shelf.bookCount)
        assertEquals(1, shelf.finishedCount)
        assertEquals("Second", shelf.nextBook?.title)
    }

    /** A finished series has nothing to carry on with, and says so rather than pointing at book one. */
    @Test
    fun `a fully finished series has no next book`() {
        val shelf = groupIntoSeries(
            listOf(
                book(id = "b1", sequence = "1", playedAt = TEST_INSTANT, isFinished = true),
                book(id = "b2", sequence = "2", playedAt = TEST_INSTANT, isFinished = true),
            ),
        ).single()

        assertEquals(2, shelf.finishedCount)
        assertNull(shelf.nextBook)
    }

    /**
     * PRODUCT_SPEC LIB-002 — searching a book title from the series tab finds the series holding it.
     *
     * Otherwise the list empties for a title the user can see two taps away, which reads as a broken
     * search rather than a narrower one.
     */
    @Test
    fun `a series matches on its own name or on any book in it`() {
        val shelf = groupIntoSeries(
            listOf(book(id = "b1", title = "Saltmarsh", sequence = "1")),
        ).single()

        assertTrue(shelf.matchesQuery("long voyage"), "the series name")
        assertTrue(shelf.matchesQuery("saltmarsh"), "a book inside it")
        assertTrue(shelf.matchesQuery("   "), "a blank query matches everything")
        assertFalse(shelf.matchesQuery("nothing here"))
    }
}
