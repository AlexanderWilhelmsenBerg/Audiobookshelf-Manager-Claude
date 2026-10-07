package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.garmin.GarminDeviceUi
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** SET-002: inline device controls; dialogs stay small, scrollable and use no fixed row height. */
@Composable
internal fun GarminDeviceCard(actions: GarminDeviceActions) {
    val state = actions.state
    var expanded by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.paired) { if (!state.paired) dialog = null }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f)) { Text(state.name) }
                if (state.connected) {
                    val connected = stringResource(R.string.garmin_connected)
                    Box(
                        Modifier.size(8.dp).background(Color(0xFF7BCB8A), CircleShape).semantics {
                            contentDescription =
                                connected
                        },
                    )
                }
            }
            if (expanded) {
                Text(
                    if (state.connected) {
                        stringResource(
                            R.string.garmin_connected,
                        )
                    } else {
                        stringResource(R.string.garmin_last_connected, date(state.lastConnected))
                    },
                )
                Text(stringResource(R.string.garmin_last_synced, date(state.lastSynced)))
                if (!state.paired) {
                    Text(stringResource(R.string.garmin_setup_hint))
                    state.pairingCode?.let { Text(stringResource(R.string.garmin_pair_code, it)) }
                    TextButton(onClick = actions.onPair, enabled = state.ready) {
                        Text(stringResource(R.string.garmin_pair))
                    }
                } else {
                    TextButton(onClick = actions.onSync) { Text(stringResource(R.string.garmin_force_sync)) }
                    Row {
                        TextButton(onClick = {
                            dialog = "downloads"
                            actions.onRefresh()
                        }) { Text(stringResource(R.string.garmin_downloads)) }
                        TextButton(onClick = { dialog = "new" }) { Text(stringResource(R.string.garmin_new_download)) }
                    }
                    TextButton(onClick = {
                        dialog = "sessions"
                        actions.onRefresh()
                    }) { Text(stringResource(R.string.garmin_sessions)) }
                    if (state.pending >
                        0
                    ) {
                        Text(pluralStringResource(R.plurals.garmin_pending, state.pending, state.pending))
                    }
                    if (state.historyGap) Text(stringResource(R.string.garmin_history_gap))
                }
                actions.message?.let { message ->
                    val resource = when (message) {
                        GarminDeviceMessage.Queued -> R.string.garmin_queued
                        GarminDeviceMessage.ConfirmWatch -> R.string.garmin_confirm_watch
                        GarminDeviceMessage.Failed -> R.string.garmin_request_failed
                    }
                    Text(stringResource(resource), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = actions.onDismissMessage) { Text(stringResource(R.string.garmin_dismiss)) }
                }
            }
        }
    }
    dialog?.let { kind -> GarminDeviceDialog(kind, actions) { dialog = null } }
}

@Composable
private fun GarminDeviceDialog(kind: String, actions: GarminDeviceActions, close: () -> Unit) {
    val state = actions.state
    val title = when (kind) {
        "new" -> R.string.garmin_new_download
        "sessions" -> R.string.garmin_sessions
        else -> R.string.garmin_downloads
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                if (kind != "new") Text(stringResource(R.string.garmin_inventory_observed, date(state.inventoryAt)))
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (kind) {
                        "new" -> watchChoices(actions, close)
                        "sessions" -> watchSessions(state)
                        else -> watchDownloads(state)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.garmin_close)) } },
    )
}

@Composable
private fun date(value: Long?): String = value?.let {
    DateTimeFormatter.ofLocalizedDateTime(
        FormatStyle.SHORT,
    ).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
}
    ?: stringResource(R.string.garmin_never)

data class GarminDeviceActions(
    val state: GarminDeviceUi = GarminDeviceUi(),
    val message: GarminDeviceMessage? = null,
    val onPair: () -> Unit = {},
    val onSync: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onDownload: (String) -> Unit = {},
    val onDismissMessage: () -> Unit = {},
)

private fun LazyListScope.watchChoices(actions: GarminDeviceActions, close: () -> Unit) {
    val state = actions.state
    item { Text(stringResource(R.string.garmin_download_hint)) }
    items(state.choices, key = { it.id }) { book ->
        TextButton(onClick = {
            actions.onDownload(book.id)
            close()
        }) { Text(book.title) }
    }
    if (state.choices.isEmpty()) {
        item {
            Text(stringResource(R.string.garmin_no_eligible_books))
        }
    }
}

private fun LazyListScope.watchSessions(state: GarminDeviceUi) {
    items(state.listens) { event ->
        Column {
            Text(event.bookTitle ?: stringResource(R.string.garmin_unknown_book))
            Text(date(event.at), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(
                    R.string.garmin_listen_progress,
                    event.positionSeconds,
                    event.durationSeconds,
                ),
            )
            if (event.kind ==
                "part_boundary"
            ) {
                Text(stringResource(R.string.garmin_part_boundary))
            }
        }
    }
    if (state.listens.isEmpty()) item { Text(stringResource(R.string.garmin_no_events)) }
}

private fun LazyListScope.watchDownloads(state: GarminDeviceUi) {
    items(state.downloads) { book ->
        Column {
            Text(book.title ?: stringResource(R.string.garmin_unknown_book))
            val status = when (book.state) {
                "downloaded" -> R.string.garmin_download_complete
                "failed" -> R.string.garmin_download_failed
                "partial" -> R.string.garmin_download_partial
                else -> R.string.garmin_download_queued
            }
            Text(stringResource(status), style = MaterialTheme.typography.bodySmall)
            Text(pluralStringResource(R.plurals.garmin_parts, book.partsTotal.toInt(), book.partsDone, book.partsTotal))
            if (book.fromSeconds > 0) Text(stringResource(R.string.garmin_available_from, book.fromSeconds))
        }
    }
    if (state.downloads.isEmpty()) {
        item {
            Text(
                stringResource(
                    if (state.inventoryAt ==
                        null
                    ) {
                        R.string.garmin_inventory_unknown
                    } else {
                        R.string.garmin_watch_empty
                    },
                ),
            )
        }
    }
}
