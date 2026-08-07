package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.domain.TEST_SERVER
import com.example.shelfplayer.domain.book
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PRODUCT_SPEC LIB-002 — the author and genre browse axes. */
class BookGroupTest {

    /**
     * A co-written book is under both names.
     *
     * Keeping only the first author is the shortcut that makes "what else is by this author" answer
     * wrong for every collaboration in the library.
     */
    @Test
    fun `a book with two authors appears under both`() {
        val shared = book(id = "b1", title = "Co-written").copy(
            authors = listOf(author("a1", "Marisol Holt"), author("a2", "Peter Nkemelu")),
        )

        val groups = groupBooks(listOf(shared), BookGroupKind.Author)

        assertEquals(listOf("Marisol Holt", "Peter Nkemelu"), groups.map { it.label })
        assertTrue(groups.all { it.books.single().title == "Co-written" })
    }

    /**
     * `Sci-Fi` and `sci-fi` are one genre.
     *
     * A genre on an Audiobookshelf server is whatever a tag editor typed, with no id behind it, so one
     * library reliably contains both spellings. Two rows for one genre is the visible bug.
     */
    @Test
    fun `genres differing only in case are one group`() {
        val groups = groupBooks(
            listOf(
                book(id = "b1", title = "First").copy(genres = listOf("Sci-Fi")),
                book(id = "b2", title = "Second").copy(genres = listOf("sci-fi")),
            ),
            BookGroupKind.Genre,
        )

        val group = groups.single()
        assertEquals("Sci-Fi", group.label, "the first spelling seen is the one shown")
        assertEquals(2, group.bookCount)
    }

    /** Blank genres are dropped rather than becoming a nameless group at the top of the list. */
    @Test
    fun `a blank genre produces no group`() {
        val groups = groupBooks(
            listOf(book(id = "b1").copy(genres = listOf("", "  "))),
            BookGroupKind.Genre,
        )

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `groups are alphabetical and their books are too`() {
        val groups = groupBooks(
            listOf(
                book(id = "b1", title = "Zenith").copy(genres = listOf("Mystery")),
                book(id = "b2", title = "Anchor").copy(genres = listOf("Mystery")),
                book(id = "b3", title = "Tide").copy(genres = listOf("Adventure")),
            ),
            BookGroupKind.Genre,
        )

        assertEquals(listOf("Adventure", "Mystery"), groups.map { it.label })
        assertEquals(listOf("Anchor", "Zenith"), groups[1].books.map { it.title })
    }

    @Test
    fun `a group matches on its own label or on a book inside it`() {
        val group = groupBooks(
            listOf(book(id = "b1", title = "Saltmarsh").copy(genres = listOf("Mystery"))),
            BookGroupKind.Genre,
        ).single()

        assertTrue(group.matchesQuery("myst"))
        assertTrue(group.matchesQuery("saltmarsh"))
        assertFalse(group.matchesQuery("nothing here"))
    }

    /** The predicate that narrows the book list has to agree with the grouping that produced the key. */
    @Test
    fun `membership is decided by the same key the grouping used`() {
        val novel = book(id = "b1").copy(genres = listOf("Sci-Fi"))

        assertTrue(novel.inGroup(BookGroupKind.Genre, "sci-fi"))
        assertFalse(novel.inGroup(BookGroupKind.Genre, "Sci-Fi"), "keys are the lowercased form")
        assertTrue(novel.inGroup(BookGroupKind.Author, "author-1"))
    }

    private fun author(id: String, name: String) = Author(TEST_SERVER, AuthorId(id), name)
}
