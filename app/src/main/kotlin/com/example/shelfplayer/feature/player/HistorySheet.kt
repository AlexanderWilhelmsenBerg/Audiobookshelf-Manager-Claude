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
 * PRODUCT_SPEC PLAY-003 / PLAY-004 — where this book has been, and a way back to any of it.
 *
 * ### Why a seek needs an undo
 *
 * It is the one playback action with no way back. The position it replaced is gone the instant it lands,
 * and on a thirty-hour book "somewhere around eleven hours" is not a position — a listener who scrubs past
 * the thing they were looking for has lost their place with no recourse. A device run put it plainly:
 * *"when seeking the multifile book back and forth it stopped"*, and there was nothing to return to.
 *
 * Every row is listed with **both ends**, and tapping one goes back to where it started. That is the whole
 * feature: the list is a record, and the record is the undo.
 *
 * ### Everything, not only this phone
 *
 * The third device run asked for two things: *"The history should also show the latest changes from the
 * server, and even more detailed on the local history. Combine them."* Both are here, and they are the same
 * list rather than two tabs, because the question a listener has — "why is my place not where I left it" —
 * is answered by the order things happened in, and a split list destroys exactly that.
 *
 * The server's side arrives as [PlaybackEvent.RemoteProgress], [PlaybackEvent.RemoteFinished] and
 * [PlaybackEvent.ServerSession] — the last being the server's own session record rather than a diff, written
 * when a refresh finds a position this device did not produce, and stamped with the server's own time so it
 * sorts where it belongs.
 *
 * The detail is three additions: the **wall-clock time** each row happened at, the **chapter** the position
 * falls in, and a **day heading** above each group. Together they turn "At 4:12:30" — which is a number —
 * into "21:04 · The Flood", which is a memory.
 *
 * ### The clouds
 *
 * PRODUCT_SPEC SYNC-002 — every in-app Play asks the server whether another device moved on first, and the
 * three `ServerCheck*` rows are what that produced. A **plain cloud** means the server was reached and
 * answered, whichever way it answered; a **struck-through cloud** means it was not — the position stood,
 * unverified, and playback carried on as it would have anyway.
 *
 * They are here because a resume on a confirmed position and a resume on an assumed one sound identical.
 * The question they answer is asked later, after a position turns out not to be where somebody left it, and
 * by then the only place the answer can live is a row in this list.
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
                    items(rowsFor(entries), key = { it.key }) { row ->
                        when (row) {
                            is HistoryRowItem.Day -> DayHeading(row.date)
                            is HistoryRowItem.Event -> HistoryRow(
                                entry = row.entry,
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

/**
 * The list as it is drawn: events with a heading above each new day.
 *
 * Built here rather than in the ViewModel because it is a *rendering* decision — a list grouped by day is
 * one list with headings, not a different set of events — and because the grouping depends on the device's
 * own zone, which is a display concern and changes without the data changing.
 */
internal sealed interface HistoryRowItem {
    val key: String

    data class Day(val date: LocalDate) : HistoryRowItem {
        override val key: String get() = "day-$date"
    }

    data class Event(val entry: PlaybackHistoryEntry) : HistoryRowItem {
        override val key: String get() = entry.id
    }
}

internal fun rowsFor(entries: List<PlaybackHistoryEntry>, zone: ZoneId = ZoneId.systemDefault()): List<HistoryRowItem> {
    val rows = mutableListOf<HistoryRowItem>()
    var lastDay: LocalDate? = null
    entries.forEach { entry ->
        val day = entry.at.atZone(zone).toLocalDate()
        if (day != lastDay) {
            rows += HistoryRowItem.Day(day)
            lastDay = day
        }
        rows += HistoryRowItem.Event(entry)
    }
    return rows
}

/**
 * `Today`, `Yesterday`, or the date.
 *
 * Named days for the two that a listener thinks in, and a date for everything older. "Tuesday" on its own
 * would be ambiguous the moment a list spans more than a week, which one that keeps 120 events easily does.
 */
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

/**
 * One event.
 *
 * **Every row is tappable**, which the first version got wrong. It made a row with no "from" a marker
 * rather than a control, on the grounds that a resume has nowhere to go back *to* — but a listener does not
 * want to go back to where a marker came from, they want to go back to *where it is*. "Take me to where I
 * fell asleep" is the single most useful thing this list does, and it is a marker that answers it.
 *
 * Three lines now rather than two: the positions, then what happened, then when and where in the book. The
 * third is the one the device run asked for, and it is the one that makes a row identifiable — a listener
 * remembers "just before I fell asleep, in the chapter about the harbour", not "4:12:30".
 */
@Composable
private fun HistoryRow(
    entry: PlaybackHistoryEntry,
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onReturnTo(entry.returnTo) }
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$spoken $whenAndWhere"
            },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entry.event.icon(),
            contentDescription = null,
            // The struck-through cloud stays the muted colour on purpose. A check that could not reach the
            // server is not an error — nothing was lost, the position simply stands unverified — and giving
            // it the error colour would make an ordinary offline Play look like a failure.
            tint = if (entry.event.isFromServer) {
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
                    // Both ends, in the order they happened. An arrow rather than a sentence: the list is
                    // scanned, not read, and a column of "11:04:12 → 3:20:00" lines has a shape.
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
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Undo,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/*
 * `CyclomaticComplexMethod` is suppressed on the three exhaustive `when`s below, and the reason is the
 * same each time: the metric counts branches, and one branch per enum value is precisely what makes these
 * correct. There is no logic in them to simplify — a `Map` would drop to complexity 1 and lose the
 * property they exist for, which is that adding a `PlaybackEvent` **fails to compile here** instead of
 * reaching a listener as a blank row. `OutputDevices.kindOf` carries the same suppression for the same
 * reason.
 */
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
    // A different cloud from RemoteProgress's: that row says a position arrived, this one says somebody
    // sat and listened on another device, which is a different thing to read at a glance.
    PlaybackEvent.ServerSession -> Icons.Filled.CloudDownload
    // PRODUCT_SPEC SYNC-002 — a plain cloud for "the server was reached and it answered", whichever way it
    // answered, and a struck-through cloud for "it was not". The pair is readable at a glance down the
    // list, which is the whole point of putting the check here: a run of clouds says every resume this
    // evening was verified, and one gap says which one was not.
    PlaybackEvent.ServerCheckAhead -> Icons.Filled.Cloud
    PlaybackEvent.ServerCheckCurrent -> Icons.Filled.Cloud
    PlaybackEvent.ServerCheckUnavailable -> Icons.Filled.CloudOff
}

/**
 * The time of day the event happened, in the device's zone.
 *
 * Minutes, not seconds. This is the field a listener matches against their own memory of the evening, and
 * `21:04:37` is three characters of noise on a line that is already carrying a chapter name.
 */
private fun Instant.asWallClock(): String = TIME_FORMAT.format(atZone(ZoneId.systemDefault()))

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Localised, because a date is the one thing on this sheet whose order differs by country. */
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private const val WEIGHT_FILL = 1f

/** Nearly full screen, like the event log: a list at half height shows four rows. */
private const val SHEET_HEIGHT = 0.9f
