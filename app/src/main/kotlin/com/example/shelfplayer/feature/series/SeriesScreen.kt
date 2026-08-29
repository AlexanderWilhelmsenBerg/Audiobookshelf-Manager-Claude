package com.example.shelfplayer.feature.series

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.designsystem.component.ShelfEmptyState
import com.example.shelfplayer.core.designsystem.component.ShelfLoadingState
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.domain.library.SeriesShelf
import com.example.shelfplayer.feature.browse.BookCard
import com.example.shelfplayer.feature.browse.BookCoverThumbnail
import com.example.shelfplayer.feature.browse.readable

@Composable
fun SeriesRoute(
    onBookSelected: (LibraryItemId) -> Unit,
    /** PRODUCT_SPEC LIB-003 — start or resume a book from the series itself. */
    onBookPlaySelected: (LibraryItemId) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeriesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SeriesScreen(
        uiState = uiState,
        onBookSelected = onBookSelected,
        onBookPlaySelected = onBookPlaySelected,
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
    onBookPlaySelected: (LibraryItemId) -> Unit,
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
                onBookPlaySelected = onBookPlaySelected,
                contentPadding = innerPadding,
            )
        }
    }
}

@Composable
private fun SeriesBooks(
    shelf: SeriesShelf,
    onBookSelected: (LibraryItemId) -> Unit,
    onBookPlaySelected: (LibraryItemId) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = HEADER_KEY) {
            SeriesHeader(shelf = shelf, onContinue = { onBookPlaySelected(it) })
        }
        items(items = shelf.books, key = { it.id.value }) { book ->
            BookCard(
                book = book,
                onClick = { onBookSelected(book.id) },
                // The membership for *this* series, not the book's first one — see [BookCard].
                membership = book.seriesMemberships.firstOrNull { it.series.id == shelf.series.id },
                // LIB-003 asks for direct play/resume on a series. The rows here are one story in order,
                // so "carry on with this one" is a common intent rather than a mis-tap waiting to happen.
                onPlay = { onBookPlaySelected(book.id) },
            )
        }
    }
}

/**
 * PRODUCT_SPEC LIB-003 — what a series is, above the books in it.
 *
 * ### What is here, and what is deliberately not
 *
 * A cover, how far through the series the listener is, how long the whole thing runs, and one button that
 * carries on. The gap this closes also asked for a **summary**, and there is none: [Series] carries a
 * server id, an id and a name, and nothing else. Inventing one from the first book's description would
 * describe the wrong thing, and reading a real one means a new API field and a contract capture first
 * (PRODUCT_SPEC 22.4). Recorded as still open rather than filled with something plausible.
 *
 * The name is in the top bar and not repeated here. A screen that says the same words twice in the first
 * two rows is the defect `docs/gaps.md` records against the book detail, and copying it here to fill space
 * would be adding a known problem on purpose.
 */
@Composable
private fun SeriesHeader(shelf: SeriesShelf, onContinue: (LibraryItemId) -> Unit, modifier: Modifier = Modifier) {
    val next = shelf.nextBook
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The cover of the book the listener would carry on with, which is the one this screen is about.
        // The first book only when everything is finished, so the square is never empty.
        BookCoverThumbnail(book = next ?: shelf.books.first(), modifier = Modifier.size(COVER_SIZE))
        Column(
            modifier = Modifier.weight(WEIGHT_FILL),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.series_progress,
                    shelf.bookCount,
                    shelf.finishedCount,
                    shelf.bookCount,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.series_total_duration, shelf.totalDuration.readable()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (next == null) {
                // Every book finished. No button: "carry on" has nowhere to go, and a control that
                // restarts a finished series on one tap is the wrong thing to leave under a thumb.
                Text(
                    text = stringResource(R.string.series_all_finished),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Seen and heard say different things, deliberately. The label is the title alone, because
                // the button sits under "N of M finished" and the surrounding words are already on screen.
                // A screen reader gets none of that context, and "Book 3, button" does not say what pressing
                // it does — so the spoken name is the whole sentence. A test caught this: the title alone
                // matched both this button and the row for the same book, which is what a listener hears.
                val spoken = stringResource(R.string.series_continue, next.title)
                Button(
                    onClick = { onContinue(next.id) },
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .semantics { contentDescription = spoken },
                ) {
                    Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = next.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The header is not a book, so it needs a key that no book id can collide with. */
private const val HEADER_KEY = "series-header"

private val COVER_SIZE = 112.dp

private const val WEIGHT_FILL = 1f
