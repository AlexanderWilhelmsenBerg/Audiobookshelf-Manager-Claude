package com.example.shelfplayer.core.model.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC MGR-003 — "existing non-empty fields are not overwritten without an explicit choice".
 *
 * The whole reason MGR-003 is built on search rather than on quick match. Quick match takes the provider's
 * first result and writes all of it; these tests are the alternative, and each one is a way a provider
 * could damage somebody's library if the rule were relaxed.
 */
class MatchCandidateTest {

    /** A field the provider does not know must never be able to erase one the user has. */
    @Test
    fun `a field the candidate has no value for is never offered`() {
        val sparse = candidate(publisher = null, isbn = null)

        val offered = sparse.changesAgainst(current())

        assertFalse(BookMetadataField.Publisher in offered)
        assertFalse(BookMetadataField.Isbn in offered)
    }

    @Test
    fun `a field the candidate agrees with is not offered as a change`() {
        val agreeing = candidate(title = "The Salt Harbour")

        assertFalse(BookMetadataField.Title in agreeing.changesAgainst(current()))
    }

    @Test
    fun `a field the candidate would change is offered`() {
        val offered = candidate().changesAgainst(current())

        assertTrue(BookMetadataField.Title in offered)
        assertTrue(BookMetadataField.PublishedYear in offered)
    }

    /**
     * **The rule the requirement names.** Only the ticked fields move; everything else is left exactly as
     * the user had it, even where the candidate has a value it would happily supply.
     */
    @Test
    fun `only the chosen fields are applied`() {
        val applied = candidate().applyTo(current(), setOf(BookMetadataField.Title))

        assertEquals("A Different Harbour", applied.title)
        // The candidate knows a publisher and a year, and neither was ticked.
        assertEquals("", applied.publisher)
        assertEquals("2024", applied.publishedYear)
    }

    @Test
    fun `an empty choice changes nothing`() {
        val before = current()

        assertEquals(before, candidate().applyTo(before, emptySet()))
    }

    /** Providers join people with commas, which is the same shape the editor's list fields use. */
    @Test
    fun `a joined author list becomes separate names`() {
        val applied = candidate(author = "Marisol Holt, Ada Fenwick")
            .applyTo(current(), setOf(BookMetadataField.Authors))

        assertEquals(listOf("Marisol Holt", "Ada Fenwick"), applied.authors)
    }

    private fun current() = BookMetadataEdit(
        title = "The Salt Harbour",
        subtitle = "",
        authors = listOf("Marisol Holt"),
        narrators = emptyList(),
        series = emptyList(),
        genres = emptyList(),
        tags = emptyList(),
        publishedYear = "2024",
        publisher = "",
        description = "",
        isbn = "",
        asin = "",
        language = "",
        isExplicit = false,
        isAbridged = false,
    )

    private fun candidate(
        title: String = "A Different Harbour",
        author: String? = "Marisol Holt",
        publisher: String? = "Tidewater",
        isbn: String? = "9780000000001",
    ) = MatchCandidate(
        provider = "google",
        title = title,
        subtitle = null,
        author = author,
        narrator = null,
        publisher = publisher,
        publishedYear = "1998",
        description = null,
        coverUrl = "https://books.example/cover.jpg",
        isbn = isbn,
        asin = null,
        genres = emptyList(),
        series = emptyList(),
        language = null,
    )
}
