package com.example.shelfplayer.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.library.BookSortOrder

@Composable
fun LibraryRoute(
    onBookSelected: (LibraryItemId) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LibraryScreen(
        uiState = uiState,
        onBookSelected = onBookSelected,
        onNavigateUp = onNavigateUp,
        onQueryChanged = viewModel::onQueryChanged,
        onOrderChanged = viewModel::onOrderChanged,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    onBookSelected: (LibraryItemId) -> Unit,
    onNavigateUp: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onOrderChanged: (BookSortOrder) -> Unit,
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
            OutlinedTextField(
                value = uiState.query,
                onValueChange = onQueryChanged,
                singleLine = true,
                label = { Text(text = stringResource(R.string.library_search_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            SortRow(selected = uiState.order, onOrderChanged = onOrderChanged)

            when {
                uiState.isLoading -> ShelfLoadingState(
                    label = stringResource(R.string.library_loading),
                )

                uiState.books.isEmpty() -> ShelfEmptyState(
                    title = stringResource(R.string.library_empty_title),
                    body = stringResource(R.string.library_empty_body),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items = uiState.books, key = { it.id.value }) { book ->
                        BookCard(book = book, onClick = { onBookSelected(book.id) })
                    }
                }
            }
        }
    }
}

/** PRODUCT_SPEC LIB-002 — sort order is a visible, one-tap choice, not a buried menu. */
@Composable
private fun SortRow(selected: BookSortOrder, onOrderChanged: (BookSortOrder) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = BookSortOrder.entries, key = { it.name }) { order ->
            FilterChip(
                selected = order == selected,
                onClick = { onOrderChanged(order) },
                label = { Text(text = stringResource(order.labelRes())) },
            )
        }
    }
}

private fun BookSortOrder.labelRes(): Int = when (this) {
    BookSortOrder.TitleAscending -> R.string.library_sort_title
    BookSortOrder.TitleDescending -> R.string.library_sort_title_desc
    BookSortOrder.AuthorAscending -> R.string.library_sort_author
    BookSortOrder.RecentlyUpdated -> R.string.library_sort_recent
    BookSortOrder.SeriesSequenceAscending -> R.string.library_sort_series
}

@Composable
private fun BookCard(book: Book, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = book.title, style = MaterialTheme.typography.titleMedium)
            book.authors.firstOrNull()?.let { author ->
                Text(
                    text = author.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            book.seriesMemberships.firstOrNull()?.let { membership ->
                Text(
                    text = stringResource(
                        R.string.book_series_position,
                        membership.series.name,
                        membership.sequence.raw.ifEmpty { "—" },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
