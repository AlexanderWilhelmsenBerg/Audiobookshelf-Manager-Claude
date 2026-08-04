package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.library.Series
import com.example.shelfplayer.core.model.library.SeriesMembership
import com.example.shelfplayer.domain.TEST_INSTANT
import com.example.shelfplayer.domain.TEST_SERVER
import com.example.shelfplayer.domain.book
import org.junit.Test
import kotlin.test.assertEquals

/** PRODUCT_SPEC LIB-002 / LIB-003 — library ordering. */
class BookSortOrderTest {

    private val library = listOf(
        book(id = "b-10", title = "The Last Sounding", sequence = "10"),
        book(id = "b-2", title = "The Weather Glass", sequence = "2"),
        book(id = "b-prequel", title = "Before the Harbour", sequence = "Prequel"),
        book(id = "b-1", title = "The Salt Harbour", sequence = "1"),
        book(id = "b-2-5", title = "A Chart of Small Islands", sequence = "2.5"),
    )

    @Test
    fun `series order is numeric, with non-numeric sequences last`() {
        val sorted = sortBooks(library, BookSortOrder.SeriesSequenceAscending).map { it.id.value }

        assertEquals(listOf("b-1", "b-2", "b-2-5", "b-10", "b-prequel"), sorted)
    }

    @Test
    fun `title order is case-insensitive and ascending`() {
        val sorted = sortBooks(library, BookSortOrder.TitleAscending).map { it.title }

        assertEquals(
            listOf(
                "A Chart of Small Islands",
                "Before the Harbour",
                "The Last Sounding",
                "The Salt Harbour",
                "The Weather Glass",
            ),
            sorted,
        )
    }

    @Test
    fun `descending title order is the exact reverse of ascending`() {
        val ascending = sortBooks(library, BookSortOrder.TitleAscending).map { it.id.value }
        val descending = sortBooks(library, BookSortOrder.TitleDescending).map { it.id.value }

        assertEquals(ascending.reversed(), descending)
    }

    @Test
    fun `recently updated puts the newest first and falls back to the fetch time`() {
        val books = listOf(
            book(id = "old", updatedAt = TEST_INSTANT.minusSeconds(60)),
            book(id = "new", updatedAt = TEST_INSTANT.plusSeconds(60)),
            book(id = "never", updatedAt = null),
        )

        assertEquals(
            listOf("new", "never", "old"),
            sortBooks(books, BookSortOrder.RecentlyUpdated).map { it.id.value },
        )
    }

    /** Sorting must be total: equal keys still produce one deterministic order. */
    @Test
    fun `books without a sequence keep a deterministic order`() {
        val unsequenced = listOf(book(id = "b"), book(id = "a"), book(id = "c"))
        val first = sortBooks(unsequenced, BookSortOrder.SeriesSequenceAscending).map { it.id.value }
        val second = sortBooks(unsequenced.reversed(), BookSortOrder.SeriesSequenceAscending)
            .map { it.id.value }

        assertEquals(first, second)
    }

    /**
     * PRODUCT_SPEC LIB-003 — a book in several series sorts by the one marked primary.
     *
     * Without this, a book's position in the grid would depend on which series the server happened
     * to list first.
     */
    @Test
    fun `a multi-series book sorts by its primary membership`() {
        val multi = book(id = "multi", sequence = "9").let { base ->
            base.copy(
                seriesMemberships = listOf(
                    SeriesMembership(
                        series = Series(TEST_SERVER, SeriesId("other"), "Other series"),
                        sequence = SeriesSequence.parse("9"),
                        isPrimary = false,
                    ),
                    SeriesMembership(
                        series = Series(TEST_SERVER, SeriesId("series-1"), "The Long Voyage"),
                        sequence = SeriesSequence.parse("1.5"),
                        isPrimary = true,
                    ),
                ),
            )
        }

        assertEquals(SeriesSequence.parse("1.5"), multi.primarySequence())

        val sorted = sortBooks(library + multi, BookSortOrder.SeriesSequenceAscending)
            .map { it.id.value }
        assertEquals(listOf("b-1", "multi", "b-2", "b-2-5", "b-10", "b-prequel"), sorted)
    }

    @Test
    fun `a book with no series at all reports an absent sequence`() {
        assertEquals(SeriesSequence.Absent, book(id = "lonely").primarySequence())
    }
}
