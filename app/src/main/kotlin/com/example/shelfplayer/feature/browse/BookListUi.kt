package com.example.shelfplayer.feature.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.SeriesMembership
import com.example.shelfplayer.domain.library.BookSortOrder
import com.example.shelfplayer.ui.glass.GlassCard
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * The book row and the sort chips, shared by the shelf of every accessible book and by a single
 * library.
 *
 * Both screens are the same list of the same model with the same affordances, so they are one
 * composable. Two copies would drift, and the first thing to drift would be the progress line — the
 * part a user checks against what they were actually listening to.
 *
 * The cover runs the **full height of the card**, flush to its left edge, against a fixed [ROW_HEIGHT].
 *
 * Fixed rather than measured from the text. Intrinsic height made a row's height depend on how long its
 * title was and on whether it had an author and a series at all, so a list of them was visibly ragged
 * and the covers came out at different sizes down the page. Every row is now identical: two lines of
 * title whether or not the title needs them, one line of author whether or not there is one.
 *
 * A book with no cover still gets the box, so the text column starts in the same place on every row —
 * a list where some rows indent and some do not is harder to scan than one with a few empty squares.
 */
@Composable
internal fun BookCard(
    book: Book,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * PRODUCT_SPEC LIB-003 — which of the book's series to name on the card.
     *
     * Defaults to the first, which is all a general shelf can know. A series screen passes the series
     * the user navigated through: a book can be third in one series and first in another, and the
     * number that belongs to some other series is worse than no number at all.
     */
    membership: SeriesMembership? = book.seriesMemberships.firstOrNull(),
    /**
     * PRODUCT_SPEC LIB-003 / LIB-004 — start or resume this book without opening it first.
     *
     * `null` on every shelf that has no opinion about playing, which is all of them but the series screen:
     * a general list's job is to get you to a book, and a play button on each row of a search result is a
     * tap that starts audio next to the tap that was meant to look at something. LIB-003 asks for it on a
     * series specifically, where the rows are one story in order and *carry on* is the common intent.
     */
    onPlay: (() -> Unit)? = null,
) {
    GlassCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            // A **fixed** height rather than `IntrinsicSize.Min`.
            //
            // Intrinsic height measured the text column, so a card's height depended on how many lines
            // its title took and on whether it had an author and a series at all — which made a list of
            // them visibly ragged, with the covers at different sizes down the page. Fixing the height
            // makes every row identical and gives the cover a definite box to fill.
            modifier = Modifier.height(ROW_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCoverThumbnail(book = book, modifier = Modifier.coverPadding())
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Two lines, always — reserved rather than earned, so a short title does not make its
                // row shorter than the one above it.
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    minLines = TITLE_LINES,
                    maxLines = TITLE_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                // Drawn blank when the book has no author, for the same reason.
                Text(
                    text = book.authors.firstOrNull()?.name.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                membership?.let { inSeries ->
                    Text(
                        text = stringResource(
                            R.string.book_series_position,
                            inSeries.series.name,
                            inSeries.sequence.raw.ifEmpty { "—" },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BookProgressLine(book)
            }
            onPlay?.let { play ->
                IconButton(onClick = play, modifier = Modifier.padding(end = 8.dp)) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        // Names the book, because a list of identical "Play" buttons tells a screen-reader
                        // user which control they are on and nothing about what it will start.
                        contentDescription = stringResource(R.string.book_play_named, book.title),
                    )
                }
            }
        }
    }
}

/**
 * PRODUCT_SPEC LIB-004 — progress is visible in the list, not only on the detail screen.
 *
 * The shelf opens ordered by what was played last, and an order the user cannot see the basis for
 * reads as an arbitrary one. This is the visible basis.
 */
@Composable
private fun BookProgressLine(book: Book, modifier: Modifier = Modifier) {
    val progress = book.progress
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = when {
                progress == null -> stringResource(R.string.book_length, book.duration.readable())
                progress.isFinished -> stringResource(R.string.book_finished_of, book.duration.readable())
                else -> stringResource(
                    R.string.book_position,
                    progress.position.readable(),
                    (progress.duration - progress.position).coerceAtLeast(Duration.ZERO).readable(),
                    progress.duration.readable(),
                    (progress.fractionComplete * 100).roundToInt(),
                )
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (progress != null && !progress.isFinished) {
            LinearProgressIndicator(
                progress = { progress.fractionComplete },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * `4h 12m`, or `12m` under an hour. Shared with the series header, so a duration reads the same
 * wherever the app shows one.
 *
 * Seconds are dropped deliberately: an audiobook's remaining time is a rough answer to "will I finish this
 * on the way home", and a ticking seconds field invites a precision the value does not have.
 */
internal fun Duration.readable(): String {
    val hours = inWholeHours
    val minutes = inWholeMinutes % MINUTES_PER_HOUR
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private const val MINUTES_PER_HOUR = 60

/**
 * Every row in the flat list, whatever its book.
 *
 * Enough for a two-line title, a line of author, a line of series and the progress bar at the
 * type scale the card uses. Fixed rather than measured, which is the only way a list of them is
 * uniform — see `BookCard`.
 */
private val ROW_HEIGHT = 132.dp

/** Two lines, always. */
private const val TITLE_LINES = 2

/** PRODUCT_SPEC LIB-002 — sort order is a visible, one-tap choice, not a buried menu. */
@Composable
internal fun BookSortRow(
    selected: BookSortOrder,
    onOrderChanged: (BookSortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
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
    BookSortOrder.LastPlayed -> R.string.library_sort_last_played
    BookSortOrder.TitleAscending -> R.string.library_sort_title
    BookSortOrder.TitleDescending -> R.string.library_sort_title_desc
    BookSortOrder.AuthorAscending -> R.string.library_sort_author
    BookSortOrder.RecentlyUpdated -> R.string.library_sort_recent
    BookSortOrder.RecentlyAdded -> R.string.library_sort_added
    BookSortOrder.SeriesSequenceAscending -> R.string.library_sort_series
}
