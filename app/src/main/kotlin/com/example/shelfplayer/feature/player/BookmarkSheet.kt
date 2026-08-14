package com.example.shelfplayer.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.library.Bookmark
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.domain.playback.GlobalTimeline
import kotlin.time.Duration

/**
 * PRODUCT_SPEC 11.1 / section 8 item 4 — the positions this listener wanted to keep.
 *
 * ### Why the row is a jump and the pencil is a rename
 *
 * A bookmark's only job is to take you back, so the row itself does that — the same behaviour every row in
 * the history pane has, for the same reason. Renaming and deleting are deliberate actions behind their own
 * targets, because a mis-tap that deletes a note is a mis-tap that destroys something the listener wrote.
 *
 * ### Positions, and the chapter they fall in
 *
 * A bookmark with no note shows its position and its chapter, which is usually enough — "2:41:07 · The
 * Flood" is a recognisable place in a book. A note replaces the chapter line rather than being squeezed
 * beside it: if the listener bothered to write something, that is the thing worth reading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkSheet(
    bookmarks: List<Bookmark>,
    chapters: List<Chapter>,
    actions: BookmarkActions,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf<Bookmark?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxHeight(SHEET_HEIGHT)) {
            Text(
                text = stringResource(R.string.player_bookmarks),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = stringResource(R.string.player_bookmarks_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            if (bookmarks.isEmpty()) {
                Text(
                    text = stringResource(R.string.player_bookmarks_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(bookmarks, key = { it.at.inWholeSeconds }) { bookmark ->
                        BookmarkRow(
                            bookmark = bookmark,
                            chapters = chapters,
                            onGoTo = {
                                actions.onGoTo(bookmark.at)
                                onDismiss()
                            },
                            onRename = { renaming = bookmark },
                            onRemove = { actions.onRemove(bookmark.at) },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { bookmark ->
        RenameDialog(
            bookmark = bookmark,
            onConfirm = { title ->
                actions.onRename(bookmark.at, title)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}

/**
 * What a bookmark row can do, bundled.
 *
 * Four callbacks would be four parameters on the sheet and four more on every row; the bundle is the same
 * device `PlayerActions` and `SkipControls` use, and it exists so a caller cannot wire three of them and
 * forget the fourth.
 */
data class BookmarkActions(
    val onGoTo: (Duration) -> Unit,
    val onRename: (Duration, String) -> Unit,
    val onRemove: (Duration) -> Unit,
)

@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    chapters: List<Chapter>,
    onGoTo: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clock = bookmark.at.asChapterClock()
    val chapter = GlobalTimeline.chapterAt(chapters, bookmark.at)?.title?.takeIf(String::isNotBlank)
    val note = bookmark.title.takeIf(String::isNotBlank)
    val caption = note ?: chapter
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onGoTo)
            .padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Bookmark,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(
            modifier = Modifier
                .weight(WEIGHT_FILL)
                // The whole row is the jump, so it is announced as one target with one action rather than
                // as three separate strings a screen reader reads in sequence.
                .semantics(mergeDescendants = true) {
                    contentDescription = caption?.let { "$clock, $it" } ?: clock
                },
        ) {
            Text(text = clock, style = MaterialTheme.typography.bodyMedium)
            caption?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onRename) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.player_bookmark_rename, clock),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.player_bookmark_delete, clock),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Renaming, which is the only text this app asks a listener to type about a book.
 *
 * The position is in the title rather than editable, because a bookmark *is* its position — the server
 * keys them by it, and changing one means deleting and creating. Saying so in the dialog's heading is
 * cheaper than a control that appears to move it.
 */
@Composable
private fun RenameDialog(
    bookmark: Bookmark,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(bookmark.at) { mutableStateOf(bookmark.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(text = stringResource(R.string.player_bookmark_note_at, bookmark.at.asChapterClock())) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(text = stringResource(R.string.player_bookmark_note)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(text = stringResource(R.string.player_bookmark_note_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.player_bookmark_note_cancel))
            }
        },
    )
}

private const val WEIGHT_FILL = 1f

/** Nearly full screen, like the history pane: a list at half height shows four rows. */
private const val SHEET_HEIGHT = 0.9f
