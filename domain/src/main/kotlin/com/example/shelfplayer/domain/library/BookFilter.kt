package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.LocalAvailability

/**
 * PRODUCT_SPEC LIB-002 — the shelves the requirement names, as filters rather than as sorts.
 *
 * "Continue listening" and "downloaded" are subsets of the library, not orderings of it. Building them
 * as sort orders was the tempting shortcut and is wrong in a way the user notices: a sort that puts
 * finished books last still shows them, and "continue listening" that lists a book you finished last
 * month is not the shelf that was asked for.
 *
 * Composable with [BookSortOrder], deliberately — narrowing to what is in progress and then ordering
 * that by title is a reasonable thing to want, and would be impossible if the two were one control.
 */
enum class BookFilter {
    /** Everything the profile can see in this library. */
    All,

    /**
     * Started and not finished.
     *
     * Excludes books with no progress at all. A shelf of everything-you-have-not-finished is the whole
     * library, which is the list this filter exists to be an alternative to.
     */
    ContinueListening,

    /**
     * PRODUCT_SPEC DL-001 — complete on disk, and therefore playable with no network.
     *
     * [LocalAvailability.Partial] is excluded: a part-downloaded book is not playable offline, and a
     * "downloaded" shelf whose contents stop halfway through is worse than one that omits them.
     */
    Downloaded,
    ;

    companion object {
        val Default: BookFilter = All
    }
}

fun filterBooks(books: List<Book>, filter: BookFilter): List<Book> = when (filter) {
    BookFilter.All -> books
    BookFilter.ContinueListening -> books.filter { book ->
        book.progress?.let { !it.isFinished } == true
    }

    BookFilter.Downloaded -> books.filter { it.localAvailability == LocalAvailability.Complete }
}
