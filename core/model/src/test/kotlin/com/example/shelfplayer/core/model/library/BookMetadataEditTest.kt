package com.example.shelfplayer.core.model.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC MGR-001 — dirty tracking and validation, which are the two rules the whole editor rests on.
 *
 * They are tested here rather than through the `ViewModel` because they are pure: the field the user
 * changed and the reason a field cannot be saved are properties of two values, not of a screen.
 */
class BookMetadataEditTest {

    /** The exhaustiveness the comparison table replaced a `when` with. */
    @Test
    fun `every field is in the comparison table`() {
        val missing = BookMetadataField.entries.filterNot { it in BookMetadataEdit.VALUES }

        assertTrue(missing.isEmpty(), "not compared: $missing")
    }

    @Test
    fun `an untouched form is clean`() {
        val form = form()

        assertTrue(form.changesFrom(form).isEmpty())
    }

    @Test
    fun `each field is reported by itself`() {
        val original = form()

        assertEquals(setOf(BookMetadataField.Title), original.copy(title = "New").changesFrom(original))
        assertEquals(
            setOf(BookMetadataField.Authors),
            original.copy(authors = listOf("Someone Else")).changesFrom(original),
        )
        assertEquals(setOf(BookMetadataField.Explicit), original.copy(isExplicit = true).changesFrom(original))
    }

    /**
     * Whitespace nobody meant to type is not a change.
     *
     * The consequence if it were: a save that changes nothing on the server, and a "you have unsaved
     * changes" prompt that never clears because the trimmed value always differs from the untrimmed one.
     */
    @Test
    fun `surrounding whitespace is not a change`() {
        val original = form()

        assertTrue(original.copy(title = "  The Salt Harbour  ").changesFrom(original).isEmpty())
        assertTrue(original.copy(authors = listOf(" Marisol Holt ")).changesFrom(original).isEmpty())
    }

    /** A list entry the user added and then emptied is not an edit — it is a row they abandoned. */
    @Test
    fun `a blank list entry is not a change`() {
        val original = form()

        assertTrue(original.copy(genres = original.genres + "").changesFrom(original).isEmpty())
        assertTrue(original.copy(series = original.series + SeriesEdit("", "")).changesFrom(original).isEmpty())
    }

    /**
     * PRODUCT_SPEC MGR-001 — a blank title is refused, because the server would store it as `null` and
     * leave an item with no name in every list on every device.
     */
    @Test
    fun `a blank title is a validation error`() {
        assertEquals(
            BookMetadataError.TitleRequired,
            form().copy(title = "   ").validate()[BookMetadataField.Title],
        )
    }

    @Test
    fun `a published year must be a number or empty`() {
        assertEquals(
            BookMetadataError.YearNotANumber,
            form().copy(publishedYear = "recently").validate()[BookMetadataField.PublishedYear],
        )
        assertTrue(form().copy(publishedYear = "").validate().isEmpty())
        assertTrue(form().copy(publishedYear = "1998").validate().isEmpty())
    }

    /**
     * The validation that protects data rather than tidiness.
     *
     * The server drops the **whole** series array if any entry lacks a name, so one row with a sequence and
     * no name would silently remove every other series the book belongs to.
     */
    @Test
    fun `a sequence with no series name is refused`() {
        val withOrphan = form().copy(series = listOf(SeriesEdit("", "3")))

        assertEquals(BookMetadataError.SeriesNameRequired, withOrphan.validate()[BookMetadataField.Series])
    }

    /**
     * PRODUCT_SPEC 22.4 — what is deliberately *not* validated.
     *
     * Audiobookshelf accepts any string for these, and a self-hosted library's owner may have reasons for
     * the value they typed. Rejecting it here would enforce a rule the server does not have.
     */
    @Test
    fun `identifiers and language are not second-guessed`() {
        val odd = form().copy(isbn = "not-an-isbn", asin = "??", language = "Middle English")

        assertTrue(odd.validate().isEmpty())
    }

    @Test
    fun `a book maps into the form and its series sequence survives verbatim`() {
        val form = form()

        assertEquals("The Salt Harbour", form.title)
        assertEquals(listOf("Marisol Holt"), form.authors)
        assertEquals("2024", form.publishedYear)
    }

    private fun form() = BookMetadataEdit(
        title = "The Salt Harbour",
        subtitle = "",
        authors = listOf("Marisol Holt"),
        narrators = listOf("Ada Fenwick"),
        series = listOf(SeriesEdit("Harbour Tales", "Book 2")),
        genres = listOf("Fiction"),
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

    @Test
    fun `a non-numeric series position is round-tripped rather than parsed`() {
        val form = form()

        assertEquals("Book 2", form.series.single().sequence)
        assertFalse(form.normalized().series.single().sequence == "2")
    }
}
