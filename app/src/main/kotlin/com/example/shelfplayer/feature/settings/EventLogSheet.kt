package com.example.shelfplayer.feature.settings

import android.content.ClipData
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.common.log.LogLevel
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
    /*
     * The filter is `rememberSaveable` UI state rather than view-model state, which keeps
     * `EventLogViewModel`'s original argument intact: the buffer is the whole truth and is handed over
     * untouched, and what is narrowed here is a copy of it for display. The rule itself lives in
     * `EventLogFilter` so a JVM test can exercise every branch.
     *
     * Saveable because rotating the phone mid-investigation and losing a typed query is exactly the moment
     * somebody is least willing to retype it.
     */
    var search by rememberSaveable { mutableStateOf("") }
    var levels by rememberSaveable(saver = stringSetSaver) { mutableStateOf(emptySet<String>()) }
    var categories by rememberSaveable(saver = stringSetSaver) { mutableStateOf(emptySet<String>()) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()) }
    val clipboardLabel = stringResource(R.string.event_log_clipboard_label)

    val query = remember(search, levels, categories) {
        EventLogQuery(
            text = search,
            levels = levels.mapNotNullTo(mutableSetOf()) { name -> LogLevel.entries.find { it.name == name } },
            categories = categories,
        )
    }
    val shown = remember(events, query) { EventLogFilter.apply(events, query).asReversed() }
    val availableLevels = remember(events) { EventLogFilter.levelsIn(events) }
    val availableCategories = remember(events) { EventLogFilter.categoriesIn(events) }

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
                // Both numbers while a filter is on. A bare "12 events" over a list narrowed from four
                // hundred is the sort of half-truth that sends somebody hunting for a line that is there.
                text = if (query.isNarrowed) {
                    stringResource(R.string.event_log_count_filtered, shown.size, events.size)
                } else {
                    pluralStringResource(R.plurals.event_log_count, events.size, events.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                label = { Text(text = stringResource(R.string.event_log_search)) },
                leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (search.isNotEmpty()) {
                        IconButton(onClick = { search = "" }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.event_log_search_clear),
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
            )
            // Two chip rows, each scrolling horizontally, because nine categories and five levels do not
            // fit across a phone and wrapping them would push the log itself off the screen.
            FilterChipRow(
                labelRes = R.string.event_log_filter_level,
                options = availableLevels.map { it.name },
                selected = levels,
                onToggle = { name -> levels = levels.toggle(name) },
            )
            FilterChipRow(
                labelRes = R.string.event_log_filter_category,
                options = availableCategories,
                selected = categories,
                onToggle = { name -> categories = categories.toggle(name) },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        search = ""
                        levels = emptySet()
                        categories = emptySet()
                    },
                    enabled = query.isNarrowed,
                ) {
                    Text(text = stringResource(R.string.event_log_filter_reset))
                }
                TextButton(
                    onClick = {
                        val text = AnnotatedString(shown.joinToString("\n") { it.asLine(formatter) })
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipboardLabel, text)))
                        }
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
                    // Two different situations that look identical and need opposite reactions: an empty
                    // buffer means wait, an empty result means widen the filter.
                    text = if (events.isEmpty()) {
                        stringResource(R.string.event_log_empty)
                    } else {
                        stringResource(R.string.event_log_no_matches)
                    },
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

/**
 * One filter row: a label and a scrolling line of chips.
 *
 * Draws nothing when [options] is empty. A row reading "Level" with no chips under it is a control that
 * looks broken, and before anything has been logged both rows are empty.
 */
@Composable
private fun FilterChipRow(
    @StringRes labelRes: Int,
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        options.forEach { option ->
            FilterChip(
                selected = option in selected,
                onClick = { onToggle(option) },
                label = { Text(text = option) },
            )
        }
    }
}

/** Adds or removes, which is what every chip in this sheet does. */
private fun Set<String>.toggle(value: String): Set<String> = if (value in this) this - value else this + value

/**
 * `rememberSaveable` cannot store a `Set` on its own — the bundle takes lists — so the two chip selections
 * are saved as `ArrayList` and read back as a set.
 *
 * Worth the six lines: without it, rotating the phone drops the filter, and the moment somebody rotates is
 * usually the moment they have just found the lines they were looking for.
 */
private val stringSetSaver: Saver<MutableState<Set<String>>, ArrayList<String>> = Saver(
    save = { ArrayList(it.value) },
    restore = { mutableStateOf(it.toSet()) },
)

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

/** Nearly full screen. A log at half height shows six lines, which is not a log. */
private const val SHEET_HEIGHT = 0.92f
