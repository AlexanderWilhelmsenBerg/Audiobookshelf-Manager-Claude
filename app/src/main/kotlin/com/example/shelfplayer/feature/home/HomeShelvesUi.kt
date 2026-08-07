package com.example.shelfplayer.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.library.HomeShelves
import com.example.shelfplayer.domain.library.SeriesProgress
import com.example.shelfplayer.feature.browse.BookCover

/**
 * PRODUCT_SPEC LIB-002 — the three horizontal shelves the app opens on.
 *
 * A shelf is a *preview*, capped in the domain at twenty. The axis destinations in the bottom bar are
 * where an exhaustive list lives, and a row that scrolls for a minute is neither a preview nor a list.
 *
 * Each shelf is omitted entirely when it is empty rather than rendered with a placeholder. A new
 * account has nothing in progress and no finished series, and three headings over three blank strips
 * is a worse first screen than one heading over the books that do exist.
 */
internal fun LazyListScope.homeShelves(
    shelves: HomeShelves,
    onBookSelected: (LibraryItemId) -> Unit,
    onSeriesSelected: (SeriesId) -> Unit,
) {
    if (shelves.continueListening.isNotEmpty()) {
        item(key = "shelf-continue") {
            BookShelfRow(
                titleRes = R.string.shelf_continue_listening,
                books = shelves.continueListening,
                onBookSelected = onBookSelected,
            )
        }
    }
    if (shelves.continueSeries.isNotEmpty()) {
        item(key = "shelf-series") {
            SeriesShelfRow(entries = shelves.continueSeries, onSeriesSelected = onSeriesSelected)
        }
    }
    if (shelves.recentlyAdded.isNotEmpty()) {
        item(key = "shelf-recent") {
            BookShelfRow(
                titleRes = R.string.shelf_recently_added,
                books = shelves.recentlyAdded,
                onBookSelected = onBookSelected,
            )
        }
    }
    // Last on purpose. The first three answer "what was I doing"; these two answer "what now", which is
    // the question a user only reaches once the first three have failed to interest them — except on a
    // brand-new account, where the first three are absent and these are the whole screen.
    if (shelves.listenAgain.isNotEmpty()) {
        item(key = "shelf-again") {
            BookShelfRow(
                titleRes = R.string.shelf_listen_again,
                books = shelves.listenAgain,
                onBookSelected = onBookSelected,
            )
        }
    }
    if (shelves.discover.isNotEmpty()) {
        item(key = "shelf-discover") {
            BookShelfRow(
                titleRes = R.string.shelf_discover,
                books = shelves.discover,
                onBookSelected = onBookSelected,
            )
        }
    }
}

@Composable
private fun BookShelfRow(
    titleRes: Int,
    books: List<Book>,
    onBookSelected: (LibraryItemId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShelfHeading(text = stringResource(titleRes))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = books, key = { it.id.value }) { book ->
                ShelfCard(
                    title = book.title,
                    subtitle = book.authors.firstOrNull()?.name,
                    progress = book.progress?.fractionComplete?.takeIf { book.progress?.isFinished == false },
                    onClick = { onBookSelected(book.id) },
                    cover = { BookCover(book = book) },
                )
            }
        }
    }
}

@Composable
private fun SeriesShelfRow(
    entries: List<SeriesProgress>,
    onSeriesSelected: (SeriesId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShelfHeading(text = stringResource(R.string.shelf_continue_series))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = entries, key = { it.series.id.value }) { entry ->
                ShelfCard(
                    title = entry.series.name,
                    // The book, not the count: "Book 3 of 7" says how far through the series you are,
                    // and the title says what you would actually be starting.
                    subtitle = stringResource(
                        R.string.shelf_series_next,
                        entry.nextBook.title,
                        pluralStringResource(
                            R.plurals.shelf_series_progress,
                            entry.finishedCount,
                            entry.finishedCount,
                            entry.bookCount,
                        ),
                    ),
                    progress = entry.finishedCount.toFloat() / entry.bookCount.toFloat(),
                    onClick = { onSeriesSelected(entry.series.id) },
                    // The next book's cover stands for the series. A series has no artwork of its own,
                    // and the book the user would actually start is the most useful thing to show.
                    cover = { BookCover(book = entry.nextBook) },
                )
            }
        }
    }
}

/**
 * A fixed-width card, because a `LazyRow` of intrinsically sized cards jumps as it scrolls: each new
 * item measures its own title and the row's rhythm changes under the finger.
 *
 * The cover is a slot rather than a `Book`, because the series shelf shows the *next book's* cover for
 * a row whose subject is a series.
 */
@Composable
private fun ShelfCard(
    title: String,
    subtitle: String?,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cover: @Composable () -> Unit = {},
) {
    Card(onClick = onClick, modifier = modifier.width(SHELF_CARD_WIDTH)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            cover()
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            progress?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ShelfHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}

/** PRODUCT_SPEC 21 — sized in `dp` so it grows with the display density but not with the font scale. */
private val SHELF_CARD_WIDTH = 160.dp
