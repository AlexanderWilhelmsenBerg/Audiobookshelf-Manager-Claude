package com.example.shelfplayer.feature.home

import com.example.shelfplayer.domain.library.BookFilter
import com.example.shelfplayer.domain.library.BookFocus

/**
 * PRODUCT_SPEC LIB-002 — the browse axes, as the bottom bar presents them.
 *
 * There used to be two places to browse: the home shelf, and a separate library screen reached through
 * Settings with its own tabs, its own search field and its own sort chips. A device run put it plainly
 * — "the main screen should be the place to be, not two different places for the same functions" — and
 * it was right: the second screen was a copy of the first that had learned things the first had not.
 *
 * Collections are absent. `PRODUCT_SPEC 3.2` makes them conditional on consistent server support, and
 * `/api/libraries/{id}/collections` is a capture target that has never been run (ADR-0008). A tab that
 * renders "not supported yet" is a control that does nothing.
 */
enum class HomeAxis { Books, Series, Authors, Genres }

/**
 * Whether the Books axis shows the three shelves or one flat list.
 *
 * [Shelves] is what the app opens on. It becomes [List] on its own the moment the user asks a question
 * a row of five cards cannot answer — a search, a filter, an author — because showing a *preview* of
 * search results would be the app quietly discarding matches.
 */
enum class BooksView { Shelves, List }

/** Everything the user has asked the shelf for, none of which is persisted except the sort order. */
internal data class HomeControls(
    val query: String = "",
    val isSearching: Boolean = false,
    val axis: HomeAxis = HomeAxis.Books,
    val booksView: BooksView = BooksView.Shelves,
    val filter: BookFilter = BookFilter.Default,
    val focus: BookFocus? = null,
) {
    /**
     * The shelves answer "what was I in the middle of". A query, a filter or an author is a different
     * question, and one that has to be answered over the whole library rather than over a preview.
     */
    val effectiveView: BooksView
        get() = if (query.isNotBlank() || filter != BookFilter.All || focus != null) BooksView.List else booksView
}
