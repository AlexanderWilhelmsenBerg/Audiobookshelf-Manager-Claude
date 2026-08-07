package com.example.shelfplayer.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.designsystem.component.ShelfEmptyState
import com.example.shelfplayer.core.designsystem.component.ShelfLoadingState
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.domain.library.BookFilter

@Composable
fun LibraryRoute(
    onBookSelected: (LibraryItemId) -> Unit,
    onSeriesSelected: (SeriesId) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LibraryScreen(
        uiState = uiState,
        actions = LibraryActions(
            onBookSelected = onBookSelected,
            onSeriesSelected = onSeriesSelected,
            onGroupSelected = viewModel::onGroupSelected,
            onQueryChanged = viewModel::onQueryChanged,
            onOrderChanged = viewModel::onOrderChanged,
            onFilterChanged = viewModel::onFilterChanged,
            onTabChanged = viewModel::onTabChanged,
            onFocusCleared = viewModel::onFocusCleared,
            onNavigateUp = onNavigateUp,
        ),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(uiState: LibraryUiState, actions: LibraryActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.home_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            LibraryTabRow(selected = uiState.tab, onTabChanged = actions.onTabChanged)

            OutlinedTextField(
                value = uiState.query,
                onValueChange = actions.onQueryChanged,
                singleLine = true,
                label = { Text(text = stringResource(R.string.library_search_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // The sort and filter chips belong to the book list. A shelf of series, authors or genres
            // is ordered by name and has nothing to filter, and offering "Author (A–Z)" against a list
            // of authors would be a control that does nothing.
            if (uiState.tab == LibraryTab.Books) {
                uiState.focus?.let { focus ->
                    FocusChip(label = focus.label, onCleared = actions.onFocusCleared)
                }
                BookFilterRow(selected = uiState.filter, onFilterChanged = actions.onFilterChanged)
                BookSortRow(selected = uiState.order, onOrderChanged = actions.onOrderChanged)
            }

            LibraryContent(uiState = uiState, actions = actions)
        }
    }
}

/** PRODUCT_SPEC LIB-002 / LIB-003 — the browse axis is a visible choice, not a menu. */
@Composable
private fun LibraryTabRow(selected: LibraryTab, onTabChanged: (LibraryTab) -> Unit, modifier: Modifier = Modifier) {
    TabRow(selectedTabIndex = selected.ordinal, modifier = modifier) {
        LibraryTab.entries.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onTabChanged(tab) },
                text = { Text(text = stringResource(tab.labelRes())) },
            )
        }
    }
}

private fun LibraryTab.labelRes(): Int = when (this) {
    LibraryTab.Books -> R.string.library_tab_books
    LibraryTab.Series -> R.string.library_tab_series
    LibraryTab.Authors -> R.string.library_tab_authors
    LibraryTab.Genres -> R.string.library_tab_genres
}

/**
 * PRODUCT_SPEC LIB-002 / 21 — the book list narrowed to one author or genre says so, and says how to
 * leave.
 *
 * An `InputChip` with a trailing dismiss rather than a plain label: a narrowed list the user cannot
 * see the reason for reads as books having gone missing, and one they cannot widen again is a trap.
 */
@Composable
private fun FocusChip(label: String, onCleared: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        InputChip(
            selected = true,
            onClick = onCleared,
            label = { Text(text = label) },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.library_focus_clear, label),
                )
            },
        )
    }
}

/** PRODUCT_SPEC LIB-002 — continue listening and downloaded, as filters over the list rather than tabs. */
@Composable
private fun BookFilterRow(selected: BookFilter, onFilterChanged: (BookFilter) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = BookFilter.entries, key = { it.name }) { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onFilterChanged(filter) },
                label = { Text(text = stringResource(filter.labelRes())) },
            )
        }
    }
}

private fun BookFilter.labelRes(): Int = when (this) {
    BookFilter.All -> R.string.library_filter_all
    BookFilter.ContinueListening -> R.string.library_filter_continue
    BookFilter.Downloaded -> R.string.library_filter_downloaded
}

@Composable
private fun LibraryContent(uiState: LibraryUiState, actions: LibraryActions, modifier: Modifier = Modifier) {
    val isEmpty = when (uiState.tab) {
        LibraryTab.Books -> uiState.books.isEmpty()
        LibraryTab.Series -> uiState.series.isEmpty()
        LibraryTab.Authors, LibraryTab.Genres -> uiState.groups.isEmpty()
    }
    when {
        uiState.isLoading -> ShelfLoadingState(label = stringResource(R.string.library_loading))

        isEmpty -> ShelfEmptyState(
            title = stringResource(uiState.tab.emptyTitleRes()),
            body = stringResource(uiState.tab.emptyBodyRes()),
        )

        else -> LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (uiState.tab) {
                LibraryTab.Books -> items(items = uiState.books, key = { it.id.value }) { book ->
                    BookCard(book = book, onClick = { actions.onBookSelected(book.id) })
                }

                LibraryTab.Series -> items(items = uiState.series, key = { it.series.id.value }) { shelf ->
                    SeriesCard(shelf = shelf, onClick = { actions.onSeriesSelected(shelf.series.id) })
                }

                LibraryTab.Authors, LibraryTab.Genres ->
                    items(items = uiState.groups, key = { it.key }) { group ->
                        GroupCard(group = group, onClick = { actions.onGroupSelected(group) })
                    }
            }
        }
    }
}

private fun LibraryTab.emptyTitleRes(): Int = when (this) {
    LibraryTab.Books -> R.string.library_empty_title
    LibraryTab.Series -> R.string.series_empty_title
    LibraryTab.Authors -> R.string.library_authors_empty_title
    LibraryTab.Genres -> R.string.library_genres_empty_title
}

private fun LibraryTab.emptyBodyRes(): Int = when (this) {
    LibraryTab.Books -> R.string.library_empty_body
    LibraryTab.Series -> R.string.series_empty_body
    LibraryTab.Authors, LibraryTab.Genres -> R.string.library_groups_empty_body
}
