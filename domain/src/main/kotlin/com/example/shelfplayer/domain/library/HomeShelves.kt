package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Series
import java.time.Instant

/**
 * PRODUCT_SPEC LIB-002 — what the shelf shows when the user has not asked for anything in particular.
 *
 * Three questions, in the order someone opening an audiobook app asks them: what was I listening to,
 * what comes next in a series I am partway through, and what is new.
 *
 * Derived from Room rather than fetched. Audiobookshelf offers `/api/libraries/{id}/personalized`,
 * which computes the equivalent server-side, and ADR-0008 records why this project does not use it:
 * `PRODUCT_SPEC 6.3` requires cached browsing to work offline, and a home screen assembled by a network
 * call is a home screen that is blank on a train.
 */
data class HomeShelves(
    val continueListening: List<Book>,
    val continueSeries: List<SeriesProgress>,
    val recentlyAdded: List<Book>,
) {
    val isEmpty: Boolean
        get() = continueListening.isEmpty() && continueSeries.isEmpty() && recentlyAdded.isEmpty()

    companion object {
        val Empty = HomeShelves(emptyList(), emptyList(), emptyList())
    }
}

/**
 * A series the user is partway through, and the book to play next.
 *
 * @property lastPlayedAt the most recent progress anywhere in the series, which is what orders the
 *   shelf: the series touched most recently is the one most likely to be wanted.
 */
data class SeriesProgress(
    val series: Series,
    val nextBook: Book,
    val finishedCount: Int,
    val bookCount: Int,
    val lastPlayedAt: Instant,
)

/**
 * Builds the three shelves from one pass over the profile's visible books.
 *
 * Each shelf is capped. A shelf is a horizontal preview, not a list — the axis tabs are where an
 * exhaustive list lives — and an uncapped `LazyRow` over 490 books would allocate 490 cards to show
 * four of them.
 */
fun homeShelvesOf(books: List<Book>, limit: Int = SHELF_LIMIT): HomeShelves = HomeShelves(
    continueListening = continueListening(books, limit),
    continueSeries = continueSeries(books, limit),
    recentlyAdded = recentlyAdded(books, limit),
)

/** Started and not finished, most recently played first — the same predicate [BookFilter] uses. */
private fun continueListening(books: List<Book>, limit: Int): List<Book> =
    filterBooks(books, BookFilter.ContinueListening)
        .sortedWith(compareByDescending<Book> { it.progress?.updatedAt ?: Instant.MIN }.thenBy { it.id.value })
        .take(limit)

/**
 * Series with at least one finished book and at least one still to go.
 *
 * The "at least one finished" condition is what separates this shelf from *continue listening*. A
 * series where book one is half-read is already on that shelf; this one answers the different
 * question of what to start next, and a series nobody has finished a book of has no answer to it.
 *
 * The next book is [SeriesShelf.nextBook] — the first unfinished one in sequence — so a series read in
 * order resumes where it was left rather than at a book already heard.
 */
private fun continueSeries(books: List<Book>, limit: Int): List<SeriesProgress> = groupIntoSeries(books)
    .mapNotNull { shelf ->
        val next = shelf.nextBook ?: return@mapNotNull null
        if (shelf.finishedCount == 0) return@mapNotNull null
        val lastPlayed = shelf.books.mapNotNull { it.progress?.updatedAt }.maxOrNull()
            ?: return@mapNotNull null
        SeriesProgress(
            series = shelf.series,
            nextBook = next,
            finishedCount = shelf.finishedCount,
            bookCount = shelf.bookCount,
            lastPlayedAt = lastPlayed,
        )
    }
    .sortedWith(compareByDescending<SeriesProgress> { it.lastPlayedAt }.thenBy { it.series.id.value })
    .take(limit)

/**
 * Newest on the server first, and only books the server dated.
 *
 * A book whose `addedAt` was never fetched is dropped rather than sorted last. On the flat list a
 * trailing block of undated books is visibly "the rest"; on a five-card row it would silently become
 * the whole shelf, presenting the oldest cache entries as the newest arrivals.
 */
private fun recentlyAdded(books: List<Book>, limit: Int): List<Book> = books
    .filter { it.addedAt != null }
    .sortedWith(compareByDescending<Book> { it.addedAt }.thenBy { it.id.value })
    .take(limit)

private const val SHELF_LIMIT = 20
