package com.example.shelfplayer.feature.home

import androidx.compose.runtime.Immutable
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.domain.library.BookFilter
import com.example.shelfplayer.domain.library.BookGroup
import com.example.shelfplayer.domain.library.BookSortOrder

/**
 * Everything the home screen can do, in one parameter.
 *
 * Home is now the only browse surface — four axes, three shelves, a search, a sort row, a filter row,
 * a dismissible focus and four destinations — which is far past what a readable parameter list holds.
 * Grouping them keeps `HomeScreen` a function of `(state, actions)`, which is also what makes it
 * previewable: a preview supplies one no-op instance instead of a long row of lambdas.
 */
@Immutable
data class HomeActions(
    val onBookSelected: (LibraryItemId) -> Unit,
    /** PLAY-001 — starts the book without opening details or forcing the full player over the shelf. */
    val onBookPlaySelected: (LibraryItemId) -> Unit,
    val onSeriesSelected: (SeriesId) -> Unit,
    val onGroupSelected: (BookGroup) -> Unit,
    /** PRODUCT_SPEC MGR-008 — opens the confirmation flow for one genre group. */
    val onGenreEditRequested: (BookGroup) -> Unit,
    val onGenreEditReplacementChanged: (String) -> Unit,
    val onGenreEditConfirmed: () -> Unit,
    val onGenreEditDismissed: () -> Unit,
    val onQueryChanged: (String) -> Unit,
    val onSearchToggled: () -> Unit,
    val onAxisChanged: (HomeAxis) -> Unit,
    val onBooksViewChanged: (BooksView) -> Unit,
    val onOrderChanged: (BookSortOrder) -> Unit,
    val onFilterChanged: (BookFilter) -> Unit,
    val onFocusCleared: () -> Unit,
    val onRefresh: () -> Unit,
    val onProfilesSelected: () -> Unit,
    val onSettingsSelected: () -> Unit,
    val onSignInSelected: () -> Unit,
)
