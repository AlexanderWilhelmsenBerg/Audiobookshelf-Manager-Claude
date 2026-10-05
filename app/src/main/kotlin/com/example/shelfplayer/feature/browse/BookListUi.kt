package com.example.shelfplayer.feature.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
 * Rows keep a minimum height but grow with their metadata, including wrapped series/progress and
 * large fonts. The cover keeps a bounded square rather than taking space away from the text as a row
 * grows. Missing cover artwork retains the same geometry.
 */
@Composable
internal fun BookCard(
    book: Book,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * PRODUCT_SPEC LIB-003 — which of the book's series to name on the card.
     *
     * Defaults to the first, which is all a general shelf can know. A scoped caller can select a
     * membership: a book can be third in one series and first in another. Series detail now uses
     * its own compact SeriesBookCard rather than this general browsing row.
     */
    membership: SeriesMembership? = book.seriesMemberships.firstOrNull(),
    /**
     * PRODUCT_SPEC LIB-003 / LIB-004 — start or resume this book without opening it first.
     *
     * Optional for a caller that deliberately supports direct playback. General browsing callers leave
     * it null: their row action opens details. Series detail exposes its direct playback action through
     * the adaptive SeriesBookCard, whose text must remain readable at large font scales.
     */
    onPlay: (() -> Unit)? = null,
) {
    // LIB-002 / PRODUCT_SPEC 17.3: the scroll trace points to layer/drawing cost. Let Haze
    // sample this row's backdrop input; title, cover, progress and hit targets remain full size.
    // This candidate needs physical timing/quality acceptance; other card/chrome callers keep
    // their default. See docs/testing/2026-10-04-card-blur-sampling.md.
    GlassCard(onClick = onClick, modifier = modifier.fillMaxWidth(), scaleBlurInput = true) {
        Row(
            // LIB-002/004, spec2.10: the last metadata line must have room to wrap and draw.
            modifier = Modifier.heightIn(min = ROW_MIN_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCoverThumbnail(book = book, modifier = Modifier.coverPadding().height(ROW_MIN_HEIGHT))
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
internal fun BookProgressLine(book: Book, modifier: Modifier = Modifier) {
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

/** Baseline density; metadata may require a taller row. The cover remains this bounded size. */
private val ROW_MIN_HEIGHT = 132.dp

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
