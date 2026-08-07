package com.example.shelfplayer.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.shelfplayer.domain.library.BookSortOrder

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
        onBookSelected = onBookSelected,
        onSeriesSelected = onSeriesSelected,
        onNavigateUp = onNavigateUp,
        onQueryChanged = viewModel::onQueryChanged,
        onOrderChanged = viewModel::onOrderChanged,
        onTabChanged = viewModel::onTabChanged,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    onBookSelected: (LibraryItemId) -> Unit,
    onSeriesSelected: (SeriesId) -> Unit,
    onNavigateUp: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onOrderChanged: (BookSortOrder) -> Unit,
    onTabChanged: (LibraryTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.home_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
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
            LibraryTabRow(selected = uiState.tab, onTabChanged = onTabChanged)

            OutlinedTextField(
                value = uiState.query,
                onValueChange = onQueryChanged,
                singleLine = true,
                label = { Text(text = stringResource(R.string.library_search_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // The sort chips belong to the book list. A series shelf is ordered by series name and its
            // books by sequence, and neither is a choice — offering "Author (A–Z)" against a list of
            // series would be a control that does nothing.
            if (uiState.tab == LibraryTab.Books) {
                BookSortRow(selected = uiState.order, onOrderChanged = onOrderChanged)
            }

            LibraryContent(
                uiState = uiState,
                onBookSelected = onBookSelected,
                onSeriesSelected = onSeriesSelected,
            )
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
}

@Composable
private fun LibraryContent(
    uiState: LibraryUiState,
    onBookSelected: (LibraryItemId) -> Unit,
    onSeriesSelected: (SeriesId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isEmpty = when (uiState.tab) {
        LibraryTab.Books -> uiState.books.isEmpty()
        LibraryTab.Series -> uiState.series.isEmpty()
    }
    when {
        uiState.isLoading -> ShelfLoadingState(label = stringResource(R.string.library_loading))

        isEmpty -> ShelfEmptyState(
            title = stringResource(
                if (uiState.tab == LibraryTab.Series) R.string.series_empty_title else R.string.library_empty_title,
            ),
            body = stringResource(
                if (uiState.tab == LibraryTab.Series) R.string.series_empty_body else R.string.library_empty_body,
            ),
        )

        else -> LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.tab == LibraryTab.Books) {
                items(items = uiState.books, key = { it.id.value }) { book ->
                    BookCard(book = book, onClick = { onBookSelected(book.id) })
                }
            } else {
                items(items = uiState.series, key = { it.series.id.value }) { shelf ->
                    SeriesCard(shelf = shelf, onClick = { onSeriesSelected(shelf.series.id) })
                }
            }
        }
    }
}
