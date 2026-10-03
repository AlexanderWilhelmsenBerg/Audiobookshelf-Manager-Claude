package com.example.shelfplayer.feature.series

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.shelfplayer.feature.browse.BookCover
import com.example.shelfplayer.feature.browse.BookProgressLine
import com.example.shelfplayer.feature.browse.readable
import com.example.shelfplayer.ui.glass.GlassCard
import kotlin.time.Duration

/**
 * LIB-003 / LIB-004 / section 21: a series row grows with its text, including large system fonts.
 *
 * Hallmark revision: compact artwork and title first; sequence, listening state and progress below.
 * Playback remains a separate labelled control, and the card still opens the book's details.
 */
@Composable
internal fun SeriesBookCard(
    book: Book,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    membership: SeriesMembership? = book.seriesMemberships.firstOrNull(),
) {
    GlassCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CARD_PADDING),
            verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
                BookCover(book = book, modifier = Modifier.size(COVER_SIZE))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(TEXT_GAP),
                ) {
                    Text(text = book.title, style = MaterialTheme.typography.titleMedium)
                    book.authors.takeIf { it.isNotEmpty() }?.let { authors ->
                        Text(
                            text = authors.joinToString(", ") { it.name },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                ListeningState(book = book, modifier = Modifier.weight(1f))
                IconButton(onClick = onPlay) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.book_play_named, book.title),
                    )
                }
            }
            if (book.progress?.isFinished == true) {
                Text(
                    text = stringResource(R.string.book_length, book.duration.readable()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                BookProgressLine(book = book)
            }
        }
    }
}

@Composable
private fun ListeningState(book: Book, modifier: Modifier = Modifier) {
    val progress = book.progress
    val finished = progress?.isFinished == true
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TEXT_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (finished) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(STATE_ICON_SIZE),
            )
        }
        Text(
            text = stringResource(
                when {
                    finished -> R.string.book_finished
                    progress != null && progress.position > Duration.ZERO -> R.string.series_book_in_progress
                    else -> R.string.series_book_not_started
                },
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private val CARD_PADDING = 12.dp
private val SECTION_GAP = 12.dp
private val TEXT_GAP = 4.dp
private val COVER_SIZE = 72.dp
private val STATE_ICON_SIZE = 20.dp
