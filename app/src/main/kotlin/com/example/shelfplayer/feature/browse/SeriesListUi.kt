package com.example.shelfplayer.feature.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.domain.library.BookGroup
import com.example.shelfplayer.domain.library.BookGroupKind
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
        Row(
            modifier = Modifier.heightIn(min = SERIES_CARD_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CollectionArtwork(
                books = shelf.books,
                style = CollectionArtworkStyle.Series,
                modifier = Modifier
                    .width(SERIES_ARTWORK_WIDTH)
                    .height(SERIES_CARD_HEIGHT),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
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
                if (shelf.finishedCount > 0) {
                    LinearProgressIndicator(
                        progress = { shelf.finishedCount.toFloat() / shelf.bookCount.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * PRODUCT_SPEC LIB-002 — one author or one genre, and how many of the profile's books are under it.
 *
 * Tapping it narrows the book list rather than opening a screen; see `HomeViewModel.onGroupSelected`
 * for why.
 */
@Composable
internal fun GroupCard(
    group: BookGroup,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    editAction: GroupCardEditAction? = null,
) {
    // A Card's own click target must not wrap the edit button. On physical hardware the parent Card
    // consumed taps in that nested target and opened the genre instead of editing it. These are sibling
    // actions now: the artwork/details region opens the group and the trailing button only edits it.
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.heightIn(min = GROUP_CARD_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(role = Role.Button, onClick = onClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CollectionArtwork(
                    books = group.books,
                    style = when (group.kind) {
                        BookGroupKind.Author -> CollectionArtworkStyle.Author
                        BookGroupKind.Genre -> CollectionArtworkStyle.Genre
                    },
                    authorId = if (group.kind == BookGroupKind.Author) AuthorId(group.key) else null,
                    modifier = Modifier
                        .width(GROUP_ARTWORK_WIDTH)
                        .height(GROUP_CARD_HEIGHT),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(text = group.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = pluralStringResource(R.plurals.series_book_count, group.bookCount, group.bookCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            editAction?.let { action ->
                TextButton(
                    onClick = action.onClick,
                    enabled = action.enabled,
                    modifier = Modifier.semantics {
                        contentDescription = action.contentDescription
                        action.disabledReason?.let { stateDescription = it }
                    },
                ) {
                    Text(text = action.label)
                }
            }
        }
    }
}

/** A named secondary action; absent for author cards and profiles without the update grant. */
@Immutable
internal data class GroupCardEditAction(
    val label: String,
    val contentDescription: String,
    val enabled: Boolean,
    val disabledReason: String?,
    val onClick: () -> Unit,
)

private val SERIES_CARD_HEIGHT = 136.dp
private val SERIES_ARTWORK_WIDTH = 140.dp
private val GROUP_CARD_HEIGHT = 124.dp
private val GROUP_ARTWORK_WIDTH = 132.dp
