package com.example.shelfplayer.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.domain.library.BookGroup
import com.example.shelfplayer.domain.library.SeriesShelf

/**
 * PRODUCT_SPEC LIB-003 / TC-16 — one row per series, opening into its ordered books.
 *
 * The row answers the two questions a series list is asked: how much of it there is, and where the
 * user left off. `nextBook` is the second answer and is shown as the book's title rather than as a
 * position number, because "Book 3" means nothing without the shelf in front of you.
 */
@Composable
internal fun SeriesCard(shelf: SeriesShelf, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val count = pluralStringResource(R.plurals.series_book_count, shelf.bookCount, shelf.bookCount)
    val summary = when {
        shelf.finishedCount == shelf.bookCount ->
            stringResource(R.string.series_summary_finished, count)

        shelf.finishedCount > 0 -> stringResource(
            R.string.series_summary_progress,
            count,
            pluralStringResource(R.plurals.series_finished_count, shelf.finishedCount, shelf.finishedCount),
        )

        else -> count
    }
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = shelf.series.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            shelf.nextBook?.let { next ->
                Text(
                    text = stringResource(R.string.series_next_up, next.title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * PRODUCT_SPEC LIB-002 — one author or one genre, and how many of the profile's books are under it.
 *
 * Tapping it narrows the book list rather than opening a screen; see `LibraryViewModel.onGroupSelected`
 * for why.
 */
@Composable
internal fun GroupCard(group: BookGroup, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = group.label, style = MaterialTheme.typography.titleMedium)
            Text(
                text = pluralStringResource(R.plurals.series_book_count, group.bookCount, group.bookCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
