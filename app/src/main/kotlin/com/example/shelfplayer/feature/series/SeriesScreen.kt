package com.example.shelfplayer.feature.series

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.shelfplayer.domain.library.SeriesShelf
import com.example.shelfplayer.feature.browse.BookCard

@Composable
fun SeriesRoute(
    onBookSelected: (LibraryItemId) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeriesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SeriesScreen(
        uiState = uiState,
        onBookSelected = onBookSelected,
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )
}

/**
 * PRODUCT_SPEC LIB-003 / TC-16 — a series opened into its books, in sequence.
 *
 * Sequence order is not offered as a choice. It is the reason the screen exists; sorting a series
 * alphabetically is what the book list is for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(
    uiState: SeriesUiState,
    onBookSelected: (LibraryItemId) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(text = uiState.shelf?.series?.name ?: stringResource(R.string.series_title))
                },
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
        val shelf = uiState.shelf
        when {
            uiState.isLoading -> ShelfLoadingState(
                label = stringResource(R.string.series_loading),
                modifier = Modifier.padding(innerPadding),
            )

            shelf == null -> ShelfEmptyState(
                title = stringResource(R.string.series_missing_title),
                body = stringResource(R.string.series_missing_body),
                modifier = Modifier.padding(innerPadding),
            )

            else -> SeriesBooks(
                shelf = shelf,
                onBookSelected = onBookSelected,
                contentPadding = innerPadding,
            )
        }
    }
}

@Composable
private fun SeriesBooks(
    shelf: SeriesShelf,
    onBookSelected: (LibraryItemId) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = shelf.books, key = { it.id.value }) { book ->
            BookCard(
                book = book,
                onClick = { onBookSelected(book.id) },
                // The membership for *this* series, not the book's first one — see [BookCard].
                membership = book.seriesMemberships.firstOrNull { it.series.id == shelf.series.id },
            )
        }
    }
}
