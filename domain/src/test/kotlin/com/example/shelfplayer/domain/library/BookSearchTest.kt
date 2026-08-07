package com.example.shelfplayer.domain.library

import com.example.shelfplayer.domain.book
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PRODUCT_SPEC LIB-002 — what a search term is matched against. */
class BookSearchTest {

    private val tagged = book(
        id = "b-1",
        title = "The Salt Harbour",
        tags = listOf("favourites"),
        isbn = "978-0-00-000000-0",
        asin = "B00ABCDEFG",
    )

    @Test
    fun `free text still matches the fields it always did`() {
        assertTrue(tagged.matchesQuery("salt"))
        assertTrue(tagged.matchesQuery("marisol"), "the author")
        assertTrue(tagged.matchesQuery("ada fenwick"), "the narrator")
        assertTrue(tagged.matchesQuery("FAVOURITES"), "a tag, case-insensitively")
        assertFalse(tagged.matchesQuery("nothing here"))
    }

    /**
     * The number on a book jacket is hyphenated; the number in a metadata tag usually is not.
     *
     * Comparing digits alone is the whole reason this is matched separately from the free-text fields:
     * a `contains` would fail on exactly the form the user reads off the cover.
     */
    @Test
    fun `an ISBN matches whether or not the user types the hyphens`() {
        assertTrue(tagged.matchesQuery("9780000000000"))
        assertTrue(tagged.matchesQuery("978-0-00-000000-0"))
        assertTrue(tagged.matchesQuery("978 0 00 000000 0"))
    }

    /** A partially typed identifier is a prefix. A fragment from the middle is not something anyone types. */
    @Test
    fun `an ISBN matches on a prefix but not on a fragment from the middle`() {
        assertTrue(tagged.matchesQuery("97800"))
        assertFalse(tagged.matchesQuery("0000000000"), "the tail of the number is not a prefix of it")
    }

    @Test
    fun `an ASIN matches case-insensitively, and is not stripped of its letters`() {
        assertTrue(tagged.matchesQuery("B00ABCDEFG"))
        assertTrue(tagged.matchesQuery("b00abc"), "a prefix, case-insensitively")
        assertFalse(tagged.matchesQuery("B00-ABC"), "punctuation is significant in an ASIN")
    }

    /** A book with no identifiers is not matched by a number, and does not crash trying. */
    @Test
    fun `a book with no identifiers matches no identifier query`() {
        val plain = book(id = "b-2", title = "A Quiet Tide")

        assertFalse(plain.matchesQuery("9780000000000"))
        assertFalse(plain.matchesQuery("B00ABCDEFG"))
    }

    /** A query with no digits never reaches the ISBN comparison, so `""` cannot prefix-match everything. */
    @Test
    fun `a non-numeric query does not match every ISBN`() {
        assertFalse(tagged.matchesQuery("zzz"))
    }
}
