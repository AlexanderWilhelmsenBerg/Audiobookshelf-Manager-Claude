package com.example.shelfplayer.domain.library

import com.example.shelfplayer.domain.TEST_INSTANT
import com.example.shelfplayer.domain.book
import com.example.shelfplayer.domain.membership
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PRODUCT_SPEC LIB-002 — the three shelves the app opens on, derived from the cache. */
class HomeShelvesTest {

    private fun at(seconds: Long): Instant = TEST_INSTANT.plusSeconds(seconds)

    @Test
    fun `continue listening is what was played most recently, unfinished`() {
        val shelves = homeShelvesOf(
            listOf(
                book(id = "b1", title = "Older", playedAt = at(10)),
                book(id = "b2", title = "Newest", playedAt = at(30)),
                book(id = "b3", title = "Finished", playedAt = at(40), isFinished = true),
                book(id = "b4", title = "Never opened"),
            ),
        )

        assertEquals(listOf("Newest", "Older"), shelves.continueListening.map { it.title })
    }

    /**
     * PRODUCT_SPEC LIB-002 — "continue a series" is not "continue listening" with extra steps.
     *
     * A series whose first book is half-read is already on the other shelf. This one answers what to
     * *start* next, so it needs a finished book behind it and an unfinished one in front.
     */
    @Test
    fun `continue series needs a finished book and a next one`() {
        val voyage = listOf(
            book(id = "v1", title = "One", sequence = "1", playedAt = at(10), isFinished = true),
            book(id = "v2", title = "Two", sequence = "2"),
        )
        val halfRead = listOf(
            book(
                id = "h1",
                title = "Started",
                playedAt = at(20),
                memberships = listOf(membership(seriesId = "half", name = "Half Read", sequence = "1")),
            ),
            book(
                id = "h2",
                title = "Untouched",
                memberships = listOf(membership(seriesId = "half", name = "Half Read", sequence = "2")),
            ),
        )

        val shelves = homeShelvesOf(voyage + halfRead)

        val entry = shelves.continueSeries.single()
        assertEquals("The Long Voyage", entry.series.name)
        assertEquals("Two", entry.nextBook.title, "the first unfinished book in sequence")
        assertEquals(1, entry.finishedCount)
        assertEquals(2, entry.bookCount)
    }

    /** A series read to the end has nothing to continue, so it leaves the shelf rather than repeating. */
    @Test
    fun `a finished series is not offered to continue`() {
        val shelves = homeShelvesOf(
            listOf(
                book(id = "v1", sequence = "1", playedAt = at(10), isFinished = true),
                book(id = "v2", sequence = "2", playedAt = at(20), isFinished = true),
            ),
        )

        assertTrue(shelves.continueSeries.isEmpty())
    }

    /** The series touched most recently is the one most likely to be wanted. */
    @Test
    fun `series are ordered by the most recent progress anywhere in them`() {
        val older = listOf(
            book(
                id = "a1",
                sequence = "1",
                playedAt = at(10),
                isFinished = true,
                memberships = listOf(membership(seriesId = "a", name = "Anchor", sequence = "1")),
            ),
            book(id = "a2", memberships = listOf(membership(seriesId = "a", name = "Anchor", sequence = "2"))),
        )
        val newer = listOf(
            book(
                id = "z1",
                sequence = "1",
                playedAt = at(50),
                isFinished = true,
                memberships = listOf(membership(seriesId = "z", name = "Zenith", sequence = "1")),
            ),
            book(id = "z2", memberships = listOf(membership(seriesId = "z", name = "Zenith", sequence = "2"))),
        )

        val shelves = homeShelvesOf(older + newer)

        assertEquals(listOf("Zenith", "Anchor"), shelves.continueSeries.map { it.series.name })
    }

    /**
     * A book with no `addedAt` is left off the shelf entirely, not sorted to the end.
     *
     * On the flat list a trailing block of undated books reads as "the rest". On a row of five cards it
     * would silently *become* the shelf, presenting the oldest cache entries as the newest arrivals.
     */
    @Test
    fun `recently added omits books the server never dated`() {
        val shelves = homeShelvesOf(
            listOf(
                book(id = "b1", title = "Undated"),
                book(id = "b2", title = "Newer").copy(addedAt = at(30)),
                book(id = "b3", title = "Older").copy(addedAt = at(10)),
            ),
        )

        assertEquals(listOf("Newer", "Older"), shelves.recentlyAdded.map { it.title })
    }

    /** A shelf is a preview. An uncapped `LazyRow` over 490 books allocates 490 cards to show four. */
    @Test
    fun `each shelf is capped`() {
        val many = (1..30).map { index ->
            book(id = "b$index", title = "Book $index", playedAt = at(index.toLong()))
        }

        assertEquals(5, homeShelvesOf(many, limit = 5).continueListening.size)
    }

    @Test
    fun `an account with nothing in it produces no shelves at all`() {
        assertTrue(homeShelvesOf(emptyList()).isEmpty)
    }

    /**
     * PRODUCT_SPEC LIB-002 — a brand-new account still gets a home screen.
     *
     * This is what *Discover* is for. Continue listening and Continue a series are both structurally
     * empty on day one, and Recently added needs a server date the first sync may not have fetched —
     * so without this shelf the first thing a new user sees is nothing at all.
     */
    @Test
    fun `an untouched, undated book still fills the discover shelf`() {
        val shelves = homeShelvesOf(listOf(book(id = "b1", title = "Untouched")))

        assertEquals(listOf("Untouched"), shelves.discover.map { it.title })
        assertTrue(shelves.continueListening.isEmpty())
        assertTrue(shelves.recentlyAdded.isEmpty(), "no added date, so not on that shelf")
    }

    /** A finished book leaves *continue listening* and appears on *listen again*, not both. */
    @Test
    fun `a finished book moves to listen again`() {
        val shelves = homeShelvesOf(
            listOf(
                book(id = "b1", title = "Finished", playedAt = at(20), isFinished = true),
                book(id = "b2", title = "Started", playedAt = at(10)),
            ),
        )

        assertEquals(listOf("Finished"), shelves.listenAgain.map { it.title })
        assertEquals(listOf("Started"), shelves.continueListening.map { it.title })
        assertTrue(shelves.discover.isEmpty(), "both have progress, so neither is a discovery")
    }
}
