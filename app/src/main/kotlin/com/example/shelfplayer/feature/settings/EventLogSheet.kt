package com.example.shelfplayer.feature.settings

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.common.log.LoggedEvent
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * PRODUCT_SPEC 14.4 — the event log, as a sheet over Settings.
 *
 * ### Newest first
 *
 * The buffer holds them oldest-first, because that is the order they happened in; this shows them reversed,
 * because somebody opening this has just watched something go wrong and the line they want is the last one.
 * Reading upwards from the top then walks backwards through the event that brought them here.
 *
 * ### The filter, and the copy button
 *
 * Two things this has to support. "Show me what just happened" wants everything; "tell me what broke" wants
 * warnings and errors only, and finding four of those among four hundred info lines on a phone screen is not
 * realistic. **Copy** puts the visible lines on the clipboard so they can be pasted into a report — which is
 * the entire reason the owner asked for this.
 *
 * Copy rather than share: a share sheet hands the text to another app, and these lines describe activity on
 * somebody's private server (14.5). The clipboard keeps the decision with the person who pressed it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventLogSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EventLogViewModel = hiltViewModel(),
) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    var problemsOnly by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()) }

    val shown = remember(events, problemsOnly) {
        events.filter { !problemsOnly || it.isProblem }.asReversed()
    }
    val problemCount = remember(events) { events.count(LoggedEvent::isProblem) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxHeight(SHEET_HEIGHT)) {
            Text(
                text = stringResource(R.string.event_log_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = pluralStringResource(R.plurals.event_log_count, events.size, events.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = problemsOnly,
                    onClick = { problemsOnly = !problemsOnly },
                    label = {
                        Text(text = stringResource(R.string.event_log_problems_only, problemCount))
                    },
                )
                TextButton(
                    onClick = {
                        val text = AnnotatedString(shown.joinToString("\n") { it.asLine(formatter) })
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(LABEL, text))) }
                    },
                    enabled = shown.isNotEmpty(),
                ) {
                    Text(text = stringResource(R.string.event_log_copy))
                }
                TextButton(onClick = viewModel::onClear, enabled = events.isNotEmpty()) {
                    Text(text = stringResource(R.string.event_log_clear))
                }
            }
            if (shown.isEmpty()) {
                Text(
                    text = stringResource(R.string.event_log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(shown, key = { "${it.at.toEpochMilli()}-${it.line.hashCode()}" }) { event ->
                        EventRow(event = event, formatter = formatter)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: LoggedEvent, formatter: DateTimeFormatter, modifier: Modifier = Modifier) {
    Text(
        text = event.asLine(formatter),
        style = MaterialTheme.typography.bodySmall,
        // A problem is coloured rather than badged: this is a wall of monospaced-looking text, and the eye
        // finds a red line in it faster than it reads a level prefix.
        color = if (event.isProblem) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 3.dp),
    )
}

/**
 * `12:04:31 W Playback Playback stopped on an error errorCode=ERROR_CODE_IO_BAD_HTTP_STATUS`
 *
 * The time is local and to the second — enough to line an event up with "it stopped about then", which is
 * the only correlation anybody performs against this. The date is deliberately absent: the buffer dies with
 * the process, so everything in it is from this run of the app.
 */
private fun LoggedEvent.asLine(formatter: DateTimeFormatter): String =
    "${formatter.format(at)} ${level.name.first()} $tag $line"

/** What the clipboard entry calls itself in a paste preview. */
private const val LABEL = "ShelfPlayer event log"

/** Nearly full screen. A log at half height shows six lines, which is not a log. */
private const val SHEET_HEIGHT = 0.92f
