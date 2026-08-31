package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.SeriesMembership

/**
 * PRODUCT_SPEC 6.4 / DL-005 / PLAY-005 — *what comes after this book*, asked once and answered one way.
 *
 * Two features need it and used to disagree by construction. `SmartDownloadUseCase` had this logic inline
 * to decide what to fetch at the halfway mark, and auto-advance needs the same answer to decide what to
 * play at the end. Two copies would eventually download book 7 and then play book 8, and the only person
 * who could tell would be the one whose next book streamed on cellular data.
 *
 * ### The primary series, when a book is in several
 *
 * The server's own primary flag where it sets one, the first membership otherwise. A book in three series
 * has three "next" books and no way to choose between them; the server's opinion is the only one available,
 * and taking the first when there is none is at least stable. PRODUCT_SPEC's own acceptance criterion —
 * *"if sequence is ambiguous, app does not guess silently"* — is about an unparseable sequence rather than
 * about which series to follow, and [com.example.shelfplayer.core.model.SeriesSequence] handles that.
 */
fun Book.primarySeriesMembership(): SeriesMembership? =
    seriesMemberships.firstOrNull(SeriesMembership::isPrimary) ?: seriesMemberships.firstOrNull()

/**
 * Every accessible book in [membership]'s series, in reading order.
 *
 * Ordered by sequence, then title, then item id — a **total** order, the same one [groupIntoSeries] gives
 * the series screen. Sequence alone leaves ties to the order the list happened to arrive in, which is how
 * the same library produces two different "next books" on two runs. `SeriesSequence` is `Comparable` and
 * already knows that 10 comes after 2 rather than before it, which is the classic audiobook-series bug.
 */
fun booksInSeriesOrder(books: List<Book>, membership: SeriesMembership): List<Book> = books
    .mapNotNull { book -> book.sequenceIn(membership)?.let { book to it } }
    .sortedWith(
        compareBy<Pair<Book, com.example.shelfplayer.core.model.SeriesSequence>> { (_, sequence) -> sequence }
            .thenBy { (book, _) -> book.title.lowercase() }
            .thenBy { (book, _) -> book.id.value },
    )
    .map { (book, _) -> book }

/**
 * The book to go to after [bookId], or `null` when there is none.
 *
 * @param skipFinished when true, keeps walking past books already finished. That is what **auto-advance**
 *   wants and what **smart download** does not: playing a finished book would start it at its own end and
 *   end again immediately, which is a loop, while downloading one that is already finished is merely
 *   pointless and DL-005 has its own retention rules for it.
 *
 * Returns `null` for a book that is in no series, for a book that is not in the list at all, and for the
 * last book of a series. All three mean the same thing to a caller — *nothing follows this* — and none of
 * them is an error.
 */
fun nextInSeries(books: List<Book>, bookId: LibraryItemId, skipFinished: Boolean): Book? {
    val current = books.firstOrNull { it.id == bookId } ?: return null
    val membership = current.primarySeriesMembership() ?: return null
    val ordered = booksInSeriesOrder(books, membership)
    val index = ordered.indexOfFirst { it.id == bookId }
    if (index < 0) return null
    val later = ordered.drop(index + 1)
    return if (skipFinished) later.firstOrNull { it.progress?.isFinished != true } else later.firstOrNull()
}

/** This book's position in [membership]'s series, or `null` if it is not in that series at all. */
private fun Book.sequenceIn(membership: SeriesMembership) =
    seriesMemberships.firstOrNull { it.series.id == membership.series.id }?.sequence
