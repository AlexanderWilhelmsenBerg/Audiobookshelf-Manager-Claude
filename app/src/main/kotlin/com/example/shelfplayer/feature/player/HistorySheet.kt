package com.example.shelfplayer.feature.player

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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.PlaybackHistoryEntry
import com.example.shelfplayer.domain.playback.GlobalTimeline
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-003 / PLAY-004 — local and server-side events for the currently loaded book.
 *
 * The trailing undo button is deliberately the only control that moves playback. A #89 device run showed
 * two local `Seek` rows immediately after a successful remote-progress adoption while the History sheet was
 * being inspected. `PlaybackEvent.Seek` can only be produced through the app's seek surface, and the old
 * sheet made the entire row an invisible seek target. Keeping the row read-only and making the undo affordance
 * explicit removes that ambiguity and makes an accidental scroll/tap unable to move a many-hour audiobook.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    entries: List<PlaybackHistoryEntry>,
    chapters: List<Chapter>,
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
                text = stringResource(R.string.player_history_body_explicit_return),
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
                    items(rowsFor(entries), key = { it.key }) { row ->
                        when (row) {
                            is HistoryRowItem.Day -> DayHeading(row.date)
                            is HistoryRowItem.Event -> HistoryRow(
                                entry = row.entry,
                                check = row.check,
                                chapters = chapters,
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
}

internal sealed interface HistoryRowItem {
    val key: String

    data class Day(val date: LocalDate) : HistoryRowItem {
        override val key: String get() = "day-$date"
    }

    data class Event(val entry: PlaybackHistoryEntry, val check: PlaybackEvent? = null) : HistoryRowItem {
        override val key: String get() = entry.id
    }
}

/** Freshness checks are rendered as annotations on the Play row they belong to, not as duplicate rows. */
internal fun rowsFor(entries: List<PlaybackHistoryEntry>, zone: ZoneId = ZoneId.systemDefault()): List<HistoryRowItem> {
    val rows = mutableListOf<HistoryRowItem>()
    var lastDay: LocalDate? = null
    entries.forEachIndexed { index, entry ->
        if (entry.event.isServerCheck) return@forEachIndexed
        val day = entry.at.atZone(zone).toLocalDate()
        if (day != lastDay) {
            rows += HistoryRowItem.Day(day)
            lastDay = day
        }
        rows += HistoryRowItem.Event(entry, check = checkFor(entry, entries, index))
    }
    return rows
}

private fun checkFor(entry: PlaybackHistoryEntry, entries: List<PlaybackHistoryEntry>, index: Int): PlaybackEvent? {
    if (entry.event != PlaybackEvent.Play) return null
    return entries.asSequence()
        .drop(index + 1)
        .takeWhile { candidate -> entry.at.toEpochMilli() - candidate.at.toEpochMilli() <= CHECK_PAIRING_WINDOW }
        .firstOrNull { candidate -> candidate.event.isServerCheck }
        ?.event
}

@Composable
private fun DayHeading(date: LocalDate, modifier: Modifier = Modifier) {
    val today = LocalDate.now()
    val label = when (date) {
        today -> stringResource(R.string.player_history_today)
        today.minusDays(1) -> stringResource(R.string.player_history_yesterday)
        else -> DATE_FORMAT.format(date)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun HistoryRow(
    entry: PlaybackHistoryEntry,
    check: PlaybackEvent?,
    chapters: List<Chapter>,
    onReturnTo: (Duration) -> Unit,
    modifier: Modifier = Modifier,
) {
    val from = entry.from
    val label = stringResource(entry.event.labelRes())
    val detail = entry.detail?.let { stringResource(R.string.player_history_detail, label, it.asShortLabel()) }
    val caption = detail ?: label
    val chapter = GlobalTimeline.chapterAt(chapters, entry.to)?.title?.takeIf(String::isNotBlank)
    val time = entry.at.asWallClock()
    val whenAndWhere = chapter?.let { stringResource(R.string.player_history_when_chapter, time, it) } ?: time
    val spoken = if (from == null) {
        stringResource(R.string.player_history_started_at, caption, entry.to.asChapterClock())
    } else {
        stringResource(
            R.string.player_history_return_to,
            caption,
            from.asChapterClock(),
            entry.to.asChapterClock(),
        )
    }
    val checkLabel = check?.let { stringResource(it.labelRes()) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics {
                contentDescription = listOfNotNull(spoken, whenAndWhere, checkLabel).joinToString(" ")
            },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entry.event.icon(),
            contentDescription = null,
            tint = if (entry.event.isRemote) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(WEIGHT_FILL)) {
            Text(
                text = if (from == null) {
                    stringResource(R.string.player_history_at, entry.to.asChapterClock())
                } else {
                    stringResource(R.string.player_history_jump, from.asChapterClock(), entry.to.asChapterClock())
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = whenAndWhere,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (check != null) {
            Icon(
                imageVector = check.checkIcon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(onClick = { onReturnTo(entry.returnTo) }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Undo,
                contentDescription = spoken,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun PlaybackEvent.checkIcon(): ImageVector =
    if (this == PlaybackEvent.ServerCheckUnavailable) Icons.Filled.CloudOff else Icons.Filled.Cloud

@Suppress("CyclomaticComplexMethod")
private fun PlaybackEvent.labelRes(): Int = when (this) {
    PlaybackEvent.Seek -> R.string.player_history_seek
    PlaybackEvent.Skip -> R.string.player_history_skip
    PlaybackEvent.Chapter -> R.string.player_history_chapter
    PlaybackEvent.AutoRewind -> R.string.player_history_rewind
    PlaybackEvent.Resume -> R.string.player_history_resume
    PlaybackEvent.Play -> R.string.player_history_play
    PlaybackEvent.Pause -> R.string.player_history_pause
    PlaybackEvent.SleepTimerStarted -> R.string.player_history_timer_started
    PlaybackEvent.SleepTimerExtended -> R.string.player_history_timer_extended
    PlaybackEvent.SleepTimerExpired -> R.string.player_history_timer_expired
    PlaybackEvent.SleepTimerRewind -> R.string.player_history_timer_rewind
    PlaybackEvent.RemoteProgress -> R.string.player_history_remote
    PlaybackEvent.RemoteFinished -> R.string.player_history_remote_finished
    PlaybackEvent.ServerSession -> R.string.player_history_server_session
    PlaybackEvent.ServerCheckAhead -> R.string.player_history_check_ahead
    PlaybackEvent.ServerCheckCurrent -> R.string.player_history_check_current
    PlaybackEvent.ServerCheckUnavailable -> R.string.player_history_check_unavailable
}

@Suppress("CyclomaticComplexMethod")
private fun PlaybackEvent.icon(): ImageVector = when (this) {
    PlaybackEvent.Seek -> Icons.Filled.FastForward
    PlaybackEvent.Skip -> Icons.Filled.FastForward
    PlaybackEvent.Chapter -> Icons.AutoMirrored.Filled.MenuBook
    PlaybackEvent.AutoRewind -> Icons.Filled.Replay
    PlaybackEvent.SleepTimerRewind -> Icons.Filled.Replay
    PlaybackEvent.Resume -> Icons.Filled.PlayArrow
    PlaybackEvent.Play -> Icons.Filled.PlayArrow
    PlaybackEvent.Pause -> Icons.Filled.Pause
    PlaybackEvent.SleepTimerStarted -> Icons.Filled.Bedtime
    PlaybackEvent.SleepTimerExtended -> Icons.Filled.Bedtime
    PlaybackEvent.SleepTimerExpired -> Icons.Filled.Bedtime
    PlaybackEvent.RemoteProgress -> Icons.Filled.CloudSync
    PlaybackEvent.RemoteFinished -> Icons.Filled.CloudDone
    PlaybackEvent.ServerSession -> Icons.Filled.CloudDownload
    PlaybackEvent.ServerCheckAhead -> Icons.Filled.Cloud
    PlaybackEvent.ServerCheckCurrent -> Icons.Filled.Cloud
    PlaybackEvent.ServerCheckUnavailable -> Icons.Filled.CloudOff
}

private fun Instant.asWallClock(): String = TIME_FORMAT.format(atZone(ZoneId.systemDefault()))

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private const val CHECK_PAIRING_WINDOW = 10_000L
private const val WEIGHT_FILL = 1f
private const val SHEET_HEIGHT = 0.9f
