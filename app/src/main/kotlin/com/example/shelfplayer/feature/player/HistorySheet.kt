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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.PlaybackHistoryEntry
import com.example.shelfplayer.core.model.playback.PlaybackJump
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-003 — where this book has been, and a way back to any of it.
 *
 * ### Why a seek needs an undo
 *
 * It is the one playback action with no way back. The position it replaced is gone the instant it lands,
 * and on a thirty-hour book "somewhere around eleven hours" is not a position — a listener who scrubs past
 * the thing they were looking for has lost their place with no recourse. A device run put it plainly:
 * *"when seeking the multifile book back and forth it stopped"*, and there was nothing to return to.
 *
 * Every jump is listed with **both ends**, and tapping one goes back to where it started. That is the whole
 * feature: the list is a record, and the record is the undo.
 *
 * ### Only jumps
 *
 * Ordinary listening is a line and is not written down — recording it would be recording a clock. What is
 * here is the set of moments the position moved without the listener hearing the gap: seeks, skips, chapter
 * jumps, an auto-rewind, and where the session opened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    entries: List<PlaybackHistoryEntry>,
    onReturnTo: (Duration) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxHeight(SHEET_HEIGHT)) {
            Text(
                text = stringResource(R.string.player_history),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = stringResource(R.string.player_history_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.player_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(entries, key = { it.id }) { entry ->
                        HistoryRow(
                            entry = entry,
                            onReturnTo = { position ->
                                onReturnTo(position)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One jump.
 *
 * **A row with no `from` is not tappable**, and that is the honest thing rather than a limitation: a resume
 * entry has nowhere to go back to, so it is a marker in the list rather than a control. Making it tappable
 * to its own destination would be a button that does nothing, which is worse than none.
 */
@Composable
private fun HistoryRow(entry: PlaybackHistoryEntry, onReturnTo: (Duration) -> Unit, modifier: Modifier = Modifier) {
    val from = entry.from
    val label = stringResource(entry.jump.labelRes())
    val spoken = if (from == null) {
        stringResource(R.string.player_history_started_at, label, entry.to.asChapterClock())
    } else {
        stringResource(
            R.string.player_history_return_to,
            label,
            from.asChapterClock(),
            entry.to.asChapterClock(),
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (from == null) Modifier else Modifier.clickable { onReturnTo(from) })
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entry.jump.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(WEIGHT_FILL)) {
            Text(
                text = if (from == null) {
                    stringResource(R.string.player_history_from_start, entry.to.asChapterClock())
                } else {
                    // Both ends, in the order they happened. An arrow rather than a sentence: the list is
                    // scanned, not read, and a column of "11:04:12 → 3:20:00" lines has a shape.
                    stringResource(R.string.player_history_jump, from.asChapterClock(), entry.to.asChapterClock())
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (from != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Undo,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun PlaybackJump.labelRes(): Int = when (this) {
    PlaybackJump.Seek -> R.string.player_history_seek
    PlaybackJump.Skip -> R.string.player_history_skip
    PlaybackJump.Chapter -> R.string.player_history_chapter
    PlaybackJump.AutoRewind -> R.string.player_history_rewind
    PlaybackJump.Resume -> R.string.player_history_resume
}

private fun PlaybackJump.icon(): ImageVector = when (this) {
    PlaybackJump.Seek -> Icons.Filled.FastForward
    PlaybackJump.Skip -> Icons.Filled.FastForward
    PlaybackJump.Chapter -> Icons.AutoMirrored.Filled.MenuBook
    PlaybackJump.AutoRewind -> Icons.Filled.Replay
    PlaybackJump.Resume -> Icons.Filled.PlayArrow
}

private const val WEIGHT_FILL = 1f

/** Nearly full screen, like the event log: a list at half height shows four rows. */
private const val SHEET_HEIGHT = 0.9f
