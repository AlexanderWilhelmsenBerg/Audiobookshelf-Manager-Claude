package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Series
import com.example.shelfplayer.core.model.library.SeriesMembership
import kotlin.time.Duration

/**
 * PRODUCT_SPEC LIB-003 / TC-16 — one series and the books in it, in reading order.
 *
 * Derived rather than stored. Room holds the memberships and this groups them on read, which means a
 * series appears and disappears with the books the active profile can actually see: P1-01 filters the
 * book rows, and a series built from those rows inherits the filter instead of needing its own.
 *
 * Lives beside [sortBooks] in `:domain` for the same reason [BookSortOrder] does — it is a shape the
 * UI renders, produced by logic that has to be testable against the awkward data self-hosted servers
 * actually hold.
 */
data class SeriesShelf(
    val series: Series,
    /** Ordered by this series' own sequence, then title. Never empty: a series with no books is not built. */
    val books: List<Book>,
) {
    val bookCount: Int get() = books.size

    val totalDuration: Duration get() = books.fold(Duration.ZERO) { running, book -> running + book.duration }

    val finishedCount: Int get() = books.count { it.progress?.isFinished == true }

    /**
     * Where to carry on: the first book in sequence that is not finished.
     *
     * Deliberately the first *unfinished* one rather than the first *started* one. A series read in
     * order leaves a trail of finished books and one in progress, and the book after the last finished
     * one is the answer whether or not it has been opened yet.
     */
    val nextBook: Book? get() = books.firstOrNull { it.progress?.isFinished != true }
}

/**
 * PRODUCT_SPEC LIB-003 — groups [books] into their series.
 *
 * Three things this gets right that a naive `groupBy` does not:
 *
 *  - **A book in several series appears in each of them.** LIB-003 says a book may belong to more than
 *    one series and the UI shows every membership, so an omnibus listed under both its trilogy and a
 *    collected edition is reachable from both.
 *  - **Position is per membership.** Book 3 of one series can be book 1 of another, so the ordering
 *    uses `membership.sequence` and not [primarySequence], which answers only for the primary series.
 *  - **Order is total.** Sequence, then title, then item id — so the same input can never render two
 *    ways, and `10` never sorts before `2` (see [com.example.shelfplayer.core.model.SeriesSequence]).
 *
 * Books with no membership are absent from the result. They are not in a series; a bucket for them
 * would be a different browse axis, not this one.
 */
fun groupIntoSeries(books: List<Book>): List<SeriesShelf> {
    val grouped = mutableMapOf<SeriesId, MutableList<MembershipOf>>()
    for (book in books) {
        for (membership in book.seriesMemberships) {
            grouped.getOrPut(membership.series.id) { mutableListOf() }.add(MembershipOf(membership, book))
        }
    }
    return grouped.values
        .map { entries -> SeriesShelf(series = entries.first().membership.series, books = entries.ordered()) }
        .sortedWith(compareBy({ it.series.name.lowercase() }, { it.series.id.value }))
}

/** One book's place in one series, kept together so the sort can read both. */
private data class MembershipOf(val membership: SeriesMembership, val book: Book)

private fun List<MembershipOf>.ordered(): List<Book> = sortedWith(
    compareBy<MembershipOf> { it.membership.sequence }
        .thenBy { it.book.title.lowercase() }
        .thenBy { it.book.id.value },
).map { it.book }

/**
 * PRODUCT_SPEC LIB-002 — the search predicate for a shelf of series.
 *
 * A series matches on its own name *or* on any book inside it. Searching a book title while looking at
 * the series list should find the series that contains it — the alternative is a list that goes empty
 * for a title the user can see two taps away, and reads as a bug rather than as a narrower search.
 */
fun SeriesShelf.matchesQuery(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return series.name.contains(needle, ignoreCase = true) || books.any { it.matchesQuery(needle) }
}
