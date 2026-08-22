package com.example.shelfplayer.domain.library

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-0025 — what a 2,000-item library does to the code that has only ever seen a few hundred.
 *
 * ### This is a scale test, not a performance measurement
 *
 * Nothing here asserts a duration. A JVM stopwatch on a CI runner measures the runner's contention and
 * the JIT's mood, and a timing threshold written from one would fail on a busy machine and pass on a
 * broken change. PRODUCT_SPEC 17.3's numbers need a device and belong to the benchmark module R-25
 * describes, which does not exist yet.
 *
 * What this *can* prove, deterministically and on every pull request, is that the functions behind
 * `HomeViewModel` still return correct answers at four times the size anybody reasoned about them at —
 * `HomeViewModel` says "490 books" — and that the bounded paths stay bounded. A defect that makes
 * grouping quadratic will not fail an assertion here; a defect that makes it *wrong*, or that lets a
 * capped shelf grow with the library, will.
 *
 * ### Why 2,000
 *
 * It is the number PRODUCT_SPEC 17.3 names. ADR-0025 established that the grid the requirement attaches
 * it to does not exist in this app, and kept the size, because the size is the part of the requirement
 * that was about the library rather than about the screen.
 */
class LargeLibraryScaleTest {

    private val books = LargeLibrary.books(SIZE)

    @Test
    fun `the generated library is the size and shape the other tests assume`() {
        assertEquals(SIZE, books.size)
        assertEquals(SIZE, books.map { it.id.value }.distinct().size, "ids must be unique")

        // If the distribution collapsed to one author or one genre, every grouping assertion below would
        // pass while testing nothing. These bounds are deliberately loose: they catch a broken generator,
        // not a slightly different one.
        assertTrue(groupBooks(books, BookGroupKind.Author).size > 100, "the library must span many authors")
        assertTrue(groupBooks(books, BookGroupKind.Genre).size > 10, "and several genres")
        assertTrue(groupIntoSeries(books).size > 50, "and many series")
        assertTrue(books.count { it.progress != null } > SIZE / 4, "with enough progress to fill shelves")
    }

    /**
     * **The bounded path stays bounded.**
     *
     * This is the architectural property worth protecting, and the one a large library would break first.
     * Every home shelf is a capped preview — `homeShelvesOf` takes a limit precisely so a `LazyRow` does
     * not allocate a card per book to show four — and a change that dropped the cap would be invisible on
     * a seven-book fixture and ruinous on a real library.
     */
    @Test
    fun `every home shelf stays capped regardless of library size`() {
        val shelves = homeShelvesOf(books)

        assertTrue(shelves.continueListening.size <= SHELF_LIMIT, "continue listening")
        assertTrue(shelves.continueSeries.size <= SHELF_LIMIT, "continue series")
        assertTrue(shelves.recentlyAdded.size <= SHELF_LIMIT, "recently added")
        assertTrue(shelves.discover.size <= SHELF_LIMIT, "discover")
        assertTrue(shelves.listenAgain.size <= SHELF_LIMIT, "listen again")
    }

    /** And a smaller library produces the same shelves, so the cap is a ceiling and not a target. */
    @Test
    fun `a small library is not padded up to the cap`() {
        val shelves = homeShelvesOf(LargeLibrary.books(count = 6))

        assertTrue(shelves.continueListening.size <= 6)
        assertTrue(shelves.recentlyAdded.size <= 6)
    }

    /**
     * Grouping reconciles: no book is lost and none is invented.
     *
     * A book with three authors is deliberately in three groups (`groupBooks`'s own KDoc says so), so the
     * total is the sum of each book's key count rather than the library size.
     */
    @Test
    fun `author grouping accounts for every book`() {
        val groups = groupBooks(books, BookGroupKind.Author)

        val placed = groups.sumOf { it.books.size }
        val expected = books.sumOf { it.authors.size }
        assertEquals(expected, placed, "every author of every book gets a place, and only one")
        assertEquals(groups.size, groups.map { it.key }.distinct().size, "no duplicate group keys")
    }

    @Test
    fun `genre grouping accounts for every book`() {
        val groups = groupBooks(books, BookGroupKind.Genre)

        assertEquals(books.sumOf { it.genres.size }, groups.sumOf { it.books.size })
    }

    /**
     * Grouping is deterministic, and it is actually sorted.
     *
     * The second assertion is the one with teeth. `groupBooks` sorts by lowercased label then by key, and
     * at 2,000 books across 240 authors an ordering defect is invisible in the seven-book fixtures every
     * other test in this package uses — there are not enough labels there for a comparator to get wrong.
     */
    @Test
    fun `grouping is deterministic and ordered at scale`() {
        val groups = groupBooks(books, BookGroupKind.Author)
        val repeated = groupBooks(LargeLibrary.books(SIZE), BookGroupKind.Author)

        assertEquals(groups.map { it.key }, repeated.map { it.key }, "the same seed must group the same way")

        val labels = groups.map { it.label.lowercase() }
        assertEquals(labels.sorted(), labels, "groups must come back in label order, not insertion order")
    }

    /** Series grouping keeps every membership and never invents a series. */
    @Test
    fun `series grouping accounts for every membership`() {
        val shelves = groupIntoSeries(books)

        assertEquals(books.sumOf { it.seriesMemberships.size }, shelves.sumOf { it.books.size })
        assertEquals(shelves.size, shelves.map { it.series.id.value }.distinct().size)
    }

    /** Sorting is total: every book comes back, once, in every order the UI offers. */
    @Test
    fun `every sort order returns the whole library exactly once`() {
        for (order in BookSortOrder.entries) {
            val sorted = sortBooks(books, order)

            assertEquals(SIZE, sorted.size, "$order dropped or duplicated a book")
            assertEquals(SIZE, sorted.map { it.id.value }.distinct().size, "$order duplicated a book")
        }
    }

    /** Filtering is a subset, never a reordering that loses items outside the predicate. */
    @Test
    fun `every filter returns a subset of the library`() {
        val ids = books.map { it.id.value }.toSet()

        for (filter in BookFilter.entries) {
            val filtered = filterBooks(books, filter)

            assertTrue(filtered.size <= SIZE, "$filter cannot grow the library")
            assertTrue(filtered.all { it.id.value in ids }, "$filter must not invent a book")
            assertEquals(filtered.size, filtered.distinct().size, "$filter must not duplicate one")
        }
    }

    /** Search over the whole library returns only matches, and finds the one it should. */
    @Test
    fun `search narrows a large library without scanning wrongly`() {
        val matches = books.filter { it.matchesQuery("Title 0007") }

        assertEquals(1, matches.size, "the padded titles make this exactly one book")
        assertEquals("book-7", matches.single().id.value)
        assertTrue(books.none { it.matchesQuery("a title no book has") })
    }

    /**
     * The flat list is the unbounded path, and this records that rather than asserting it away.
     *
     * ADR-0025's second target exists because of this: `BooksView.List` materialises every visible book,
     * with no paging anywhere in the app. Nothing here can fail on it — holding 2,000 small objects on a
     * JVM is unremarkable — and the number that matters is the device's, taken by the benchmark module
     * that R-25 still describes as missing. The assertion below is only that the path is honest about its
     * size.
     */
    @Test
    fun `the flat list holds the whole library, which is the point of ADR-0025`() {
        val listed = sortBooks(filterBooks(books, BookFilter.All), BookSortOrder.entries.first())

        assertEquals(SIZE, listed.size)
    }

    private companion object {
        /** PRODUCT_SPEC 17.3's number, kept by ADR-0025 while the screen it named was not. */
        const val SIZE = 2_000
    }
}
