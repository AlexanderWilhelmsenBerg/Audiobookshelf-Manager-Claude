package com.example.shelfplayer.feature.settings

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.example.shelfplayer.diagnostics.DiagnosticRestartActivity
import com.example.shelfplayer.diagnostics.DiagnosticRestartPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

internal const val UNIFIED_DIAGNOSTICS_START = "unified-diagnostics-start"
internal const val UNIFIED_DIAGNOSTICS_COPY = "unified-diagnostics-json"
internal const val UNIFIED_DIAGNOSTICS_SAVE = "unified-diagnostics-save-json"

/**
 * One stationary-use diagnostic control, and one versioned, machine-readable JSON report.
 * Starting the recorders never alters the route. Restart confirmation is explicit because
 * Android's AudioTrack provider is fixed at Media3 player creation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedDiagnosticsSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    capture: UnifiedDiagnosticsViewModel = hiltViewModel(),
    settings: SettingsViewModel = hiltViewModel(),
    logs: EventLogViewModel = hiltViewModel(),
    crashes: CrashReportViewModel = hiltViewModel(),
) {
    val active by capture.active.collectAsStateWithLifecycle()
    val lab by capture.labState.collectAsStateWithLifecycle()
    val classifier by capture.classifierState.collectAsStateWithLifecycle()
    val race by capture.raceState.collectAsStateWithLifecycle()
    val state by settings.uiState.collectAsStateWithLifecycle()
    val metrics by settings.playbackMetrics.collectAsStateWithLifecycle()
    val events by logs.events.collectAsStateWithLifecycle()
    val crash by crashes.report.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmRestart by remember { mutableStateOf(false) }
    // Keep the exact snapshot the listener chose before opening the system file picker.
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val saveJson = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val report = pendingExport
        pendingExport = null
        if (uri != null && report != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val output = context.contentResolver.openOutputStream(uri, "wt")
                            ?: error("Document provider returned no writable stream")
                        UnifiedDiagnosticsFileExport.write(output, report)
                    }.isSuccess
                }
                Toast.makeText(
                    context,
                    if (saved) R.string.unified_diag_save_success else R.string.unified_diag_save_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    val build = state.versionLabel + " ${BuildConfig.BUILD_TYPE} ${BuildConfig.GIT_COMMIT} ${state.sourceLabel}"
    val legacy = remember(state, metrics, events, crash) {
        DiagnosticsReport.of(build, state, metrics, events, Instant.now(), crash)
    }

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.unified_diag_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.unified_diag_description), style = MaterialTheme.typography.bodySmall)
            Text(
                if (active) {
                    stringResource(R.string.unified_diag_recording)
                } else {
                    stringResource(R.string.unified_diag_stopped)
                },
                style = MaterialTheme.typography.labelLarge,
            )
            Text(lab.reportStatus, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        if (active) {
                            capture.stop()
                        } else {
                            capture.start()
                            confirmRestart = true
                        }
                    },
                    modifier = Modifier.testTag(UNIFIED_DIAGNOSTICS_START),
                ) {
                    Text(
                        if (active) {
                            stringResource(R.string.unified_diag_stop)
                        } else {
                            stringResource(R.string.unified_diag_start)
                        },
                    )
                }
                TextButton(onClick = capture::clear) { Text(stringResource(R.string.unified_diag_clear)) }
            }
            Text(stringResource(R.string.unified_diag_export_help), style = MaterialTheme.typography.bodySmall)
            TextButton(
                modifier = Modifier.testTag(UNIFIED_DIAGNOSTICS_COPY),
                onClick = {
                    val report = capture.jsonReport(build, legacy)
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("BookWave diagnostics JSON", AnnotatedString(report))),
                        )
                    }
                },
            ) { Text(stringResource(R.string.unified_diag_copy_json)) }
            TextButton(
                modifier = Modifier.testTag(UNIFIED_DIAGNOSTICS_SAVE),
                onClick = {
                    pendingExport = capture.jsonReport(build, legacy)
                    saveJson.launch(UnifiedDiagnosticsFileExport.filename(Instant.now()))
                },
            ) { Text(stringResource(R.string.unified_diag_save_json)) }
            Text(
                "Audio Route Lab: ${lab.entries.size} · Output Classifier: ${classifier.entries.size} · " +
                    "Race trace: ${race.lines.size} · Event Log: ${events.size}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text(stringResource(R.string.unified_diag_restart_title)) },
            text = { Text(stringResource(R.string.unified_diag_restart_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestart = false
                    if (DiagnosticRestartPolicy.canUseTrampoline(Build.VERSION.SDK_INT)) {
                        context.startActivity(Intent(context, DiagnosticRestartActivity::class.java))
                    } else {
                        // API 26/27 cannot use Application.getProcessName. Keep diagnostics
                        // opt-in and ask for a normal manual app restart instead.
                        Toast.makeText(context, R.string.unified_diag_restart_manual, Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.unified_diag_restart_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestart = false }) {
                    Text(stringResource(R.string.unified_diag_restart_later))
                }
            },
        )
    }
}
