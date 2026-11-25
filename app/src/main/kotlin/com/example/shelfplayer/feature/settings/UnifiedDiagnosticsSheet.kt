package com.example.shelfplayer.feature.settings

import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.BuildConfig
import com.example.shelfplayer.R
import kotlinx.coroutines.launch
import java.time.Instant

internal const val UNIFIED_DIAGNOSTICS_START = "unified-diagnostics-start"
internal const val UNIFIED_DIAGNOSTICS_COPY = "unified-diagnostics-json"
internal const val UNIFIED_DIAGNOSTICS_SAVE = "unified-diagnostics-save-json"

// Clipboard text uses UTF-16 inside Android's shared Binder transaction buffer.
private const val MAX_CLIPBOARD_CHARS = 300_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedDiagnosticsSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    capture: UnifiedDiagnosticsViewModel = hiltViewModel(),
    settings: SettingsViewModel = hiltViewModel(),
    openCarTests: Boolean = false,
) {
    val active by capture.active.collectAsStateWithLifecycle()
    val lab by capture.labState.collectAsStateWithLifecycle()
    val state by settings.uiState.collectAsStateWithLifecycle()
    val clearing by capture.clearing.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var advanced by remember { mutableStateOf(false) }
    // Keep the snapshot selected when opening the system file picker.
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val saveJson =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val report = pendingExport
            pendingExport = null
            if (uri != null && report != null) {
                scope.launch {
                    val message = if (capture.saveJson(uri, report)) {
                        R.string.unified_diag_save_success
                    } else {
                        R.string.unified_diag_save_failed
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    val build = state.versionLabel + " ${BuildConfig.BUILD_TYPE} ${BuildConfig.GIT_COMMIT} ${state.sourceLabel}"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxHeight(0.92f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.unified_diag_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.unified_diag_description), style = MaterialTheme.typography.bodySmall)
            val status = when {
                clearing -> R.string.unified_diag_clearing
                active -> R.string.unified_diag_recording
                else -> R.string.unified_diag_stopped
            }
            Text(stringResource(status), style = MaterialTheme.typography.labelLarge)
            CaptureActions(
                active = active,
                enabled = !clearing,
                onToggle = { if (active) capture.stop() else capture.start() },
                onClear = {
                    pendingExport = null
                    capture.clear()
                },
            )
            ExportActions(
                enabled = !clearing,
                onSave = {
                    pendingExport = capture.jsonReport(build)
                    saveJson.launch(UnifiedDiagnosticsFileExport.filename(Instant.now()))
                },
                onCopy = {
                    val report = capture.jsonReport(build)
                    if (report.length > MAX_CLIPBOARD_CHARS) {
                        Toast.makeText(context, R.string.unified_diag_copy_too_large, Toast.LENGTH_LONG).show()
                    } else {
                        scope.launch {
                            val text = AnnotatedString(report)
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("BookWave diagnostics JSON", text)))
                        }
                    }
                },
            )
            if (active) {
                Text(lab.reportStatus, style = MaterialTheme.typography.bodySmall)
                AudioRouteLabMarkers(enabled = !clearing, onMark = capture::mark, onHeard = capture::heardAt)
            }
            if (BuildConfig.DEBUG) {
                CarLabControls(enabled = !clearing, initiallyExpanded = openCarTests)
                TextButton(onClick = { advanced = !advanced }) {
                    Text(
                        stringResource(
                            if (advanced) R.string.unified_diag_hide_advanced else R.string.unified_diag_advanced,
                        ),
                    )
                }
                if (advanced) SystemRouteExperimentSection()
            }
        }
    }
}

@Composable
private fun CaptureActions(active: Boolean, enabled: Boolean, onToggle: () -> Unit, onClear: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            enabled = enabled,
            onClick = onToggle,
            modifier = Modifier.testTag(UNIFIED_DIAGNOSTICS_START),
        ) {
            Text(stringResource(if (active) R.string.unified_diag_stop else R.string.unified_diag_start))
        }
        TextButton(enabled = enabled, onClick = onClear) {
            Text(stringResource(R.string.unified_diag_clear))
        }
    }
}

@Composable
private fun ExportActions(enabled: Boolean, onSave: () -> Unit, onCopy: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = enabled, onClick = onSave, modifier = Modifier.testTag(UNIFIED_DIAGNOSTICS_SAVE)) {
            Text(stringResource(R.string.unified_diag_save_json))
        }
        TextButton(enabled = enabled, onClick = onCopy, modifier = Modifier.testTag(UNIFIED_DIAGNOSTICS_COPY)) {
            Text(stringResource(R.string.unified_diag_copy_json))
        }
    }
}
