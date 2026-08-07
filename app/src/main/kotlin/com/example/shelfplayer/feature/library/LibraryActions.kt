package com.example.shelfplayer.feature.library

import androidx.compose.runtime.Immutable
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.domain.library.BookFilter
import com.example.shelfplayer.domain.library.BookGroup
import com.example.shelfplayer.domain.library.BookSortOrder

/**
 * Everything the library screen can do, in one parameter — the same shape as `HomeActions` and for the
 * same reason.
 *
 * Four browse axes, a search field, a sort row, a filter row and a dismissible focus is more callbacks
 * than a readable parameter list holds, and keeping `LibraryScreen` a function of `(state, actions)` is
 * what lets a preview supply one no-op instance rather than nine lambdas.
 */
@Immutable
data class LibraryActions(
    val onBookSelected: (LibraryItemId) -> Unit,
    val onSeriesSelected: (SeriesId) -> Unit,
    val onGroupSelected: (BookGroup) -> Unit,
    val onQueryChanged: (String) -> Unit,
    val onOrderChanged: (BookSortOrder) -> Unit,
    val onFilterChanged: (BookFilter) -> Unit,
    val onTabChanged: (LibraryTab) -> Unit,
    val onFocusCleared: () -> Unit,
    val onNavigateUp: () -> Unit,
)
