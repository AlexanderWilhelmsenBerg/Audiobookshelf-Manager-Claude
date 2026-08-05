package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.library.Book

/**
 * PRODUCT_SPEC LIB-002 / LIB-003 — the orders a library grid can be shown in.
 *
 * Sorting lives here rather than in a ViewModel so it can be unit tested against the awkward cases
 * that actually occur in self-hosted libraries: decimal sequence numbers, `Prequel`, missing
 * durations and books that belong to more than one series.
 */
enum class BookSortOrder {
    TitleAscending,
    TitleDescending,
    AuthorAscending,
    RecentlyUpdated,

    /** PRODUCT_SPEC LIB-003 — numeric sequences first and numerically, everything else after. */
    SeriesSequenceAscending,
    ;

    companion object {
        val Default: BookSortOrder = TitleAscending
    }
}

/**
 * Returns [books] ordered by [order].
 *
 * The comparators are total and deterministic: every one of them falls back to the title and then to
 * the item id, so the same input can never render in two different orders between recompositions.
 */
fun sortBooks(books: List<Book>, order: BookSortOrder): List<Book> {
    val tieBreak = compareBy<Book>({ it.title.lowercase() }, { it.id.value })
    val comparator = when (order) {
        BookSortOrder.TitleAscending -> tieBreak
        BookSortOrder.TitleDescending ->
            compareByDescending<Book> { it.title.lowercase() }.then(compareBy { it.id.value })
        BookSortOrder.AuthorAscending ->
            compareBy<Book> { it.authors.firstOrNull()?.name?.lowercase().orEmpty() }.then(tieBreak)
        BookSortOrder.RecentlyUpdated ->
            compareByDescending<Book> { it.remoteUpdatedAt ?: it.lastFetchedAt }.then(tieBreak)
        BookSortOrder.SeriesSequenceAscending ->
            compareBy<Book> { it.primarySequence() }.then(tieBreak)
    }
    return books.sortedWith(comparator)
}

/**
 * PRODUCT_SPEC LIB-003 — the sequence used for ordering when a book is in several series.
 *
 * A membership explicitly marked primary wins. Otherwise the first server-provided ordered series is
 * used, which is the documented fallback; the choice is recorded on the membership rather than being
 * re-derived, so the smart downloader in Phase 4 and this sort agree.
 */
fun Book.primarySequence(): SeriesSequence = seriesMemberships.firstOrNull { it.isPrimary }?.sequence
    ?: seriesMemberships.firstOrNull()?.sequence
    ?: SeriesSequence.Absent
