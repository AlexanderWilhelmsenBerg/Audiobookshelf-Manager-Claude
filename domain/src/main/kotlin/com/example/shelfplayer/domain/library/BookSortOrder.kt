package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.library.Book
import java.time.Instant

/**
 * PRODUCT_SPEC LIB-002 / LIB-003 — the orders a library grid can be shown in.
 *
 * Sorting lives here rather than in a ViewModel so it can be unit tested against the awkward cases
 * that actually occur in self-hosted libraries: decimal sequence numbers, `Prequel`, missing
 * durations and books that belong to more than one series.
 */
enum class BookSortOrder {
    /**
     * PRODUCT_SPEC LIB-002 — the order the shelf opens in: whatever was played most recently, first.
     *
     * Books with no progress at all sort after every book that has some, in title order, so the list
     * reads as "carry on with these, and here is the rest of the shelf" rather than interleaving the
     * two.
     *
     * A *finished* book is not demoted. Its progress timestamp is as recent as any other, and "last
     * played" is what this order claims to be. Hiding finished books is the separate
     * *continue listening* shelf LIB-002 also asks for, and a filter is not a sort.
     */
    LastPlayed,
    TitleAscending,
    TitleDescending,
    AuthorAscending,
    RecentlyUpdated,

    /** PRODUCT_SPEC LIB-003 — numeric sequences first and numerically, everything else after. */
    SeriesSequenceAscending,
    ;

    companion object {
        val Default: BookSortOrder = TitleAscending

        /**
         * PRODUCT_SPEC SET-001 — resolves a stored order name, falling back to [fallback].
         *
         * Preferences hold the name rather than the ordinal, so a value written by an older build that
         * named an order this one no longer has resolves to the fallback instead of throwing or — far
         * worse — silently becoming whichever order now holds that position.
         *
         * The fallback is a parameter because the two shelves open differently and deliberately so: the
         * home shelf opens on [LastPlayed] ("carry on with these"), a single library on [Default]. A
         * shared constant would quietly make one of them wrong.
         */
        fun fromStoredName(name: String?, fallback: BookSortOrder = Default): BookSortOrder =
            entries.firstOrNull { it.name == name } ?: fallback
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
        // `Instant.MIN` rather than "no progress last" as a separate branch: descending on a value that
        // is smaller than every real timestamp puts the unplayed books after the played ones, and the
        // tie-break then orders them among themselves by title.
        BookSortOrder.LastPlayed ->
            compareByDescending<Book> { it.progress?.updatedAt ?: Instant.MIN }.then(tieBreak)
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
