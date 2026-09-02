package com.example.shelfplayer.feature.author

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.designsystem.component.ShelfEmptyState
import com.example.shelfplayer.core.designsystem.component.ShelfLoadingState
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.domain.library.AuthorShelf
import com.example.shelfplayer.feature.browse.BookCard
import com.example.shelfplayer.feature.browse.CollectionArtwork
import com.example.shelfplayer.feature.browse.CollectionArtworkStyle
import com.example.shelfplayer.feature.browse.readable
import com.example.shelfplayer.ui.glass.playerChromeClearance

@Composable
fun AuthorRoute(
    onBookSelected: (LibraryItemId) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AuthorScreen(
        uiState = uiState,
        onBookSelected = onBookSelected,
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )
}

/**
 * PRODUCT_SPEC §62 "author view" — an author opened into their books.
 *
 * ### Why this is a screen when the Authors browse axis narrows in place
 *
 * Two entry points asking the same question in different situations. Inside the library the reader is
 * already holding the sort chips, the filter chips and the search field, and `BookFocus` keeps all three
 * working — pushing a screen there would mean rebuilding them or doing without. Arriving from a book's own
 * page there is nothing to keep, and the line above says *Series*, which pushes a screen. Being asymmetric
 * with the line directly above it would be the stranger choice.
 *
 * ### No play button on the rows
 *
 * A series' rows are one story in order, so *carry on with this one* is the common intent and LIB-003 asks
 * for it. An author's rows are not in any order — several series and standalones interleaved — so a play
 * control on each is a tap that starts audio beside the tap that meant to look at something. The rule that
 * `BookCard.onPlay` defaults to absent exists for exactly this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorScreen(
    uiState: AuthorUiState,
    onBookSelected: (LibraryItemId) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = uiState.shelf?.author?.name ?: stringResource(R.string.author_title)) },
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
                label = stringResource(R.string.author_loading),
                modifier = Modifier.padding(innerPadding),
            )

            shelf == null -> ShelfEmptyState(
                title = stringResource(R.string.author_missing_title),
                body = stringResource(R.string.author_missing_body),
                modifier = Modifier.padding(innerPadding),
            )

            else -> AuthorBooks(
                shelf = shelf,
                onBookSelected = onBookSelected,
                contentPadding = innerPadding,
            )
        }
    }
}

@Composable
private fun AuthorBooks(
    shelf: AuthorShelf,
    onBookSelected: (LibraryItemId) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
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
        item(key = HEADER_KEY) { AuthorHeader(shelf = shelf) }
        items(items = shelf.books, key = { it.id.value }) { book ->
            BookCard(
                book = book,
                onClick = { onBookSelected(book.id) },
                // The book's own primary membership, since this shelf is not about any one series.
                membership = book.seriesMemberships.firstOrNull(),
            )
        }
    }
}

/**
 * The portrait, how much of this author the listener has finished, and how long the rest runs.
 *
 * The name is in the top bar and not repeated here — the same rule the series header follows, for the same
 * reason: a screen that says the same words twice in its first two rows is the defect `docs/gaps.md`
 * already records against the book detail.
 *
 * [CollectionArtwork] rather than a bare portrait, because it is the component that already knows a
 * portrait may not exist: `Author.hasPortrait` gates the request (LIB-001), and a fan of this author's own
 * cached covers is the loading, error and offline state rather than a grey square.
 */
@Composable
private fun AuthorHeader(shelf: AuthorShelf, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CollectionArtwork(
            books = shelf.books,
            style = CollectionArtworkStyle.Author,
            authorId = shelf.author.id,
            modifier = Modifier.size(PORTRAIT_SIZE),
        )
        Column(
            modifier = Modifier.weight(WEIGHT_FILL),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.author_progress,
                    shelf.bookCount,
                    shelf.finishedCount,
                    shelf.bookCount,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.author_total_duration, shelf.totalDuration.readable()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The header is not a book, so it needs a key that no book id can collide with. */
private const val HEADER_KEY = "author-header"

private val PORTRAIT_SIZE = 112.dp

private const val WEIGHT_FILL = 1f
