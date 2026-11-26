package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.playback.AndroidAutoDiagnosticsCapture
import com.example.shelfplayer.playback.AndroidAutoDrive
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun DriveHistoryPicker(
    drives: List<AndroidAutoDrive>?,
    status: AndroidAutoDiagnosticsCapture.Status,
    clearing: Boolean,
    preparing: Boolean,
    onEnable: (Boolean) -> Unit,
    onClear: () -> Unit,
    onExport: (Boolean, Set<Long>) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(DriveFilter.All) }
    var selection by rememberSaveable { mutableStateOf(longArrayOf()) }
    val visible = remember(drives, filter) { drives.orEmpty().filter(filter::includes) }
    val selected = selection.toSet()
    val ids = if (selected.isEmpty()) setOfNotNull(visible.firstOrNull()?.number) else selected
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.auto_diag_title), style = MaterialTheme.typography.titleLarge) }
        item { Text(stringResource(R.string.auto_diag_description), style = MaterialTheme.typography.bodyMedium) }
        item { DriveRecordingControl(status, clearing, onEnable) }
        item {
            DriveFilters(filter) { next ->
                if (next != filter) {
                    selection = longArrayOf()
                    filter = next
                }
            }
        }
        item {
            DriveExportActions(
                ids.size,
                selected.isEmpty(),
                !clearing && !preparing && ids.isNotEmpty(),
                onShare = { onExport(true, ids) },
                onSave = { onExport(false, ids) },
            )
        }
        item {
            DriveSelectionControl(drives, visible, selected.isEmpty()) {
                selection = if (selected.isEmpty()) visible.map { it.number }.toLongArray() else longArrayOf()
            }
        }
        items(visible, key = { it.number }) { drive ->
            DriveHistoryRow(drive, drive.number in selected) { checked ->
                selection = (if (checked) selected + drive.number else selected - drive.number).toLongArray()
            }
        }
        item {
            TextButton(enabled = !clearing && !preparing, onClick = {
                selection = longArrayOf()
                onClear()
            }) {
                Text(stringResource(if (clearing) R.string.unified_diag_clearing else R.string.auto_diag_clear))
            }
        }
    }
}

@Composable
private fun DriveRecordingControl(
    status: AndroidAutoDiagnosticsCapture.Status,
    clearing: Boolean,
    onEnable: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(
                status.enabled,
                enabled = !clearing,
                role = Role.Switch,
                onValueChange = onEnable,
            ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.auto_diag_enable), modifier = Modifier.weight(1f))
            Switch(checked = status.enabled, onCheckedChange = null, enabled = !clearing)
        }
        val current = status.recordingAttempt
        if (current != null) {
            Text(stringResource(R.string.auto_diag_recording, current))
        } else if (status.enabled) {
            Text(stringResource(R.string.auto_diag_waiting))
        }
    }
}

@Composable
private fun DriveSelectionControl(
    drives: List<AndroidAutoDrive>?,
    visible: List<AndroidAutoDrive>,
    emptySelection: Boolean,
    onClick: () -> Unit,
) {
    if (visible.isEmpty()) {
        val message = when {
            drives == null -> R.string.auto_drive_loading
            drives.isEmpty() -> R.string.auto_drive_empty
            else -> R.string.auto_drive_no_match
        }
        Text(stringResource(message))
    } else {
        Column {
            Text(stringResource(R.string.auto_drive_picker_hint), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onClick) {
                Text(stringResource(if (emptySelection) R.string.auto_select_all else R.string.auto_reset_selection))
            }
        }
    }
}

@Composable
private fun DriveFilters(filter: DriveFilter, onChange: (DriveFilter) -> Unit) {
    Text(stringResource(R.string.auto_headset_at_connection), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DriveFilter.entries.forEach {
            FilterChip(selected = filter == it, onClick = { onChange(it) }, label = { Text(stringResource(it.label)) })
        }
    }
}

@Composable
private fun DriveExportActions(
    count: Int,
    latest: Boolean,
    enabled: Boolean,
    onShare: () -> Unit,
    onSave: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = enabled, onClick = onShare) {
            Text(
                if (latest) {
                    stringResource(
                        R.string.auto_share_latest,
                    )
                } else {
                    stringResource(R.string.auto_share_selected, count)
                },
            )
        }
        TextButton(enabled = enabled, onClick = onSave) {
            Text(
                if (latest) {
                    stringResource(
                        R.string.auto_save_latest,
                    )
                } else {
                    stringResource(R.string.auto_save_selected, count)
                },
            )
        }
    }
}

@Composable
private fun DriveHistoryRow(drive: AndroidAutoDrive, selected: Boolean, onToggle: (Boolean) -> Unit) {
    val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    val status = drive.statusLabel()
    val headset = when (drive.headsetPresent) {
        true -> R.string.auto_filter_with
        false -> R.string.auto_filter_without
        null -> R.string.auto_headset_unknown
    }
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(selected, role = Role.Checkbox, onValueChange = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = selected, onCheckedChange = null)
        Column {
            Text(stringResource(R.string.auto_drive_label, drive.number, date.format(drive.started)))
            val summary = stringResource(R.string.auto_drive_summary, stringResource(headset), stringResource(status))
            Text(
                drive.ended?.let { stringResource(R.string.auto_drive_end_time, summary, date.format(it)) } ?: summary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun AndroidAutoDrive.statusLabel(): Int = when {
    observingDeparture -> R.string.auto_drive_observing_departure

    endReason == null && connected -> R.string.auto_drive_active

    endReason == null -> R.string.auto_drive_connecting

    !connected -> R.string.auto_drive_unconfirmed

    endReason == "recording-interrupted" || endReason == "service-stopped" ||
        endReason == "capture-disabled" -> R.string.auto_drive_interrupted

    else -> R.string.auto_drive_ended
}
