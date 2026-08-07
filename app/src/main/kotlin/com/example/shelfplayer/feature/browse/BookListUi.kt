package com.example.shelfplayer.feature.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.SeriesMembership
import com.example.shelfplayer.domain.library.BookSortOrder
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
 * The cover is a fixed-size thumbnail rather than a proportional one: a row whose height depends on
 * how its title wraps is a list that jitters as it scrolls. A book with no cover still gets the box,
 * so the text column starts in the same place on every row.
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
) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCoverThumbnail(book = book, modifier = Modifier.coverPadding())
            Column(
                modifier = Modifier.weight(1f),
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
 * `4h 12m`, or `12m` under an hour.
 *
 * Seconds are dropped deliberately: an audiobook's remaining time is a rough answer to "will I finish this
 * on the way home", and a ticking seconds field invites a precision the value does not have.
 */
private fun Duration.readable(): String {
    val hours = inWholeHours
    val minutes = inWholeMinutes % MINUTES_PER_HOUR
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private const val MINUTES_PER_HOUR = 60

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
