package com.example.shelfplayer.feature.series

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.designsystem.component.ShelfEmptyState
import com.example.shelfplayer.core.designsystem.component.ShelfLoadingState
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.library.SeriesOrder
import com.example.shelfplayer.domain.library.SeriesShelf
import com.example.shelfplayer.feature.browse.BookCoverThumbnail
import com.example.shelfplayer.feature.browse.readable
import com.example.shelfplayer.ui.glass.playerChromeClearance

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
    BackHandler(enabled = uiState.editor != null, onBack = viewModel::cancelEditing)
    SeriesScreen(
        uiState = uiState,
        onBookSelected = onBookSelected,
        onBookPlaySelected = onBookPlaySelected,
        onNavigateUp = onNavigateUp,
        modifier = modifier,
        editorActions = SeriesEditorActions(
            start = viewModel::startEditing,
            cancel = viewModel::cancelEditing,
            save = viewModel::save,
            move = viewModel::move,
            addMissing = viewModel::addMissing,
            removeMissing = viewModel::removeMissing,
            refresh = viewModel::refreshSavedOrder,
        ),
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
    editorActions: SeriesEditorActions = SeriesEditorActions(),
) {
    val editor = uiState.editor
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(text = uiState.shelf?.series?.name ?: stringResource(R.string.series_title))
                },
                navigationIcon = {
                    IconButton(
                        onClick = if (editor != null) editorActions.cancel else onNavigateUp,
                        enabled = editor?.saving != true,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(
                                if (editor != null) R.string.series_editor_cancel else R.string.navigate_back,
                            ),
                        )
                    }
                },
                actions = {
                    when {
                        editor != null -> TextButton(
                            onClick = editorActions.save,
                            enabled = editor.hasChanges && !editor.saving,
                        ) { Text(stringResource(R.string.series_editor_save)) }

                        uiState.canEdit -> TextButton(onClick = editorActions.start) {
                            Text(stringResource(R.string.series_editor_edit))
                        }
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

            editor != null -> SeriesEditor(
                shelf = shelf,
                editor = editor,
                actions = editorActions,
                modifier = Modifier.padding(innerPadding),
            )

            else -> SeriesBooks(
                shelf = shelf,
                onBookSelected = onBookSelected,
                onBookPlaySelected = onBookPlaySelected,
                contentPadding = innerPadding,
                missingBooks = uiState.missingBooks,
                orderRefresh = uiState.orderRefresh,
                onRefreshOrder = editorActions.refresh,
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
    missingBooks: Set<Int>,
    orderRefresh: SeriesOrderRefresh?,
    onRefreshOrder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = remember(shelf.books, shelf.series.id, missingBooks) { shelf.withPlaceholders(missingBooks) }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(contentPadding),
        // The mini player floats over this screen; the list has to be able to scroll clear of it.
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = 16.dp + playerChromeClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (orderRefresh != null) {
            item(key = REFRESH_KEY) {
                SavedOrderRefresh(state = orderRefresh, onClick = onRefreshOrder)
            }
        }
        item(key = HEADER_KEY) {
            SeriesHeader(shelf = shelf, onContinue = { onBookPlaySelected(it) })
        }
        items(items = entries, key = { it.key }) { entry ->
            when (entry) {
                is SeriesBrowseEntry.Missing -> SeriesMissingCard(entry.number)

                is SeriesBrowseEntry.Present -> {
                    val book = entry.book
                    SeriesBookCard(
                        book = book,
                        onClick = { onBookSelected(book.id) },
                        membership = book.seriesMemberships.firstOrNull { it.series.id == shelf.series.id },
                        onPlay = { onBookPlaySelected(book.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedOrderRefresh(state: SeriesOrderRefresh, onClick: () -> Unit) {
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(stringResource(R.string.series_order_saved_refresh_needed), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onClick, enabled = state != SeriesOrderRefresh.Refreshing) {
            Text(
                stringResource(
                    if (state == SeriesOrderRefresh.Refreshing) {
                        R.string.series_order_refreshing
                    } else {
                        R.string.series_order_refresh
                    },
                ),
            )
        }
    }
}

private sealed interface SeriesBrowseEntry {
    val key: String
    val sequence: SeriesSequence

    data class Present(val book: Book, override val sequence: SeriesSequence) : SeriesBrowseEntry {
        override val key: String = "book:${book.id.value}"
    }

    data class Missing(val number: Int) : SeriesBrowseEntry {
        override val key: String = "missing:$number"
        override val sequence: SeriesSequence = SeriesSequence.parse(number.toString())
    }
}

private fun SeriesShelf.withPlaceholders(numbers: Set<Int>): List<SeriesBrowseEntry> {
    val present = books.map { book ->
        val sequence = book.seriesMemberships.firstOrNull { it.series.id == series.id }?.sequence
        SeriesBrowseEntry.Present(book, sequence ?: SeriesSequence.Absent)
    }
    val occupied = present.mapNotNull { SeriesOrder.occupiedNumber(it.sequence) }.toSet()
    val missing = (numbers - occupied).map { SeriesBrowseEntry.Missing(it) }
    return (present + missing).sortedBy { it.sequence }
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
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // The next book's cover, or the first when everything is finished.
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
            }
        }
        if (next == null) {
            // Do not offer a one-tap restart of the whole finished series.
            Text(
                text = stringResource(R.string.series_all_finished),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Full title outside the button; its short label stays readable at large text sizes.
            Text(text = next.title, style = MaterialTheme.typography.titleMedium)
            val spoken = stringResource(R.string.series_continue, next.title)
            Button(
                onClick = { onContinue(next.id) },
                modifier = Modifier.semantics { contentDescription = spoken },
            ) {
                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(text = stringResource(R.string.series_continue_action))
            }
        }
    }
}

/** The header is not a book, so it needs a key that no book id can collide with. */
private const val HEADER_KEY = "series-header"
private const val REFRESH_KEY = "series-order-refresh"

private val COVER_SIZE = 80.dp

private const val WEIGHT_FILL = 1f
