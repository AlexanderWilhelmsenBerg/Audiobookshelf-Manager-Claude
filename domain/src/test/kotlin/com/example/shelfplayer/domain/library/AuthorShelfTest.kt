package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.domain.TEST_INSTANT
import com.example.shelfplayer.domain.TEST_SERVER
import com.example.shelfplayer.domain.book
import com.example.shelfplayer.domain.membership
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** LIB-002/003/004, PD-006: author groups never stand in for the full series catalogue. */
class AuthorShelfTest {
    private val authorId = AuthorId("author-1")

    @Test
    fun `every membership is grouped and only membership free books are standalone`() {
        val shared =
            book(
                "shared",
                memberships = listOf(membership("z", "Voyage", "2"), membership("a", "Collected", "1", false)),
            )
        val standalone = book("alone", title = "A standalone")
        val coauthored = book("co", sequence = "1").copy(
            authors =
            listOf(Author(TEST_SERVER, AuthorId("other"), "Other")) + shared.authors,
        )
        val shelf = requireNotNull(authorShelfFor(listOf(shared, standalone, coauthored, shared), authorId, true))

        assertEquals(3, shelf.bookCount)
        assertEquals(listOf("Collected", "The Long Voyage", "Voyage"), shelf.series.map { it.series.name })
        assertEquals(listOf("alone"), shelf.standaloneBooks.map { it.id.value })
        assertTrue(shelf.catalogueComplete)
    }

    @Test
    fun `unfinished other author member prevents false series completion`() {
        val finished = book("finished", sequence = "1", playedAt = TEST_INSTANT, isFinished = true)
        val other = book(
            "other",
            sequence = "2",
            playedAt = TEST_INSTANT,
        ).copy(authors = listOf(Author(TEST_SERVER, AuthorId("other"), "Other")))
        val shelf = requireNotNull(authorShelfFor(listOf(finished, other), authorId, true))

        assertEquals(listOf("finished"), shelf.books.map { it.id.value })
        assertEquals(listOf("finished", "other"), shelf.series.single().books.map { it.id.value })
        assertEquals(1, shelf.series.single().finishedCount)
        assertEquals(2, shelf.series.single().bookCount)
    }

    @Test
    fun `unknown progress is not finished and an unverified catalogue remains unverified`() {
        val finished = book("finished", sequence = "1", playedAt = TEST_INSTANT, isFinished = true)
        val unknown = book("unknown", sequence = "2")
        val shelf = requireNotNull(authorShelfFor(listOf(finished, unknown), authorId))

        assertFalse(shelf.catalogueComplete)
        assertEquals(1, shelf.series.single().finishedCount)
        assertEquals(2, shelf.series.single().bookCount)
    }

    @Test
    fun `standalone order is stable and richest matching portrait survives`() {
        val portrait = book("z", "Same").let {
            it.copy(authors = it.authors.map { author -> author.copy(hasPortrait = true) })
        }
        val shelf = requireNotNull(authorShelfFor(listOf(portrait, book("a", "same"), book("b", "Before")), authorId))

        assertEquals(listOf("b", "a", "z"), shelf.standaloneBooks.map { it.id.value })
        assertTrue(shelf.author.hasPortrait)
        assertTrue(shelf.series.isEmpty())
    }

    @Test
    fun `missing or revoked author has no private projection`() {
        assertNull(authorShelfFor(listOf(book("unrelated")), AuthorId("revoked")))
        assertNull(authorShelfFor(emptyList(), authorId))
    }
}
