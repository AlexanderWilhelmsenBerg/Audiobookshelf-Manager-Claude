package com.example.shelfplayer.feature.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.BuildConfig
import com.example.shelfplayer.R
import kotlinx.coroutines.launch
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AndroidAutoDiagnosticsSheet(
    onDismiss: () -> Unit,
    capture: AndroidAutoDiagnosticsViewModel = hiltViewModel(),
    settings: SettingsViewModel = hiltViewModel(),
) {
    val status by capture.status.collectAsStateWithLifecycle()
    val clearing by capture.clearing.collectAsStateWithLifecycle()
    val settingsState by settings.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val build = "${settingsState.versionLabel} ${BuildConfig.BUILD_TYPE} " +
        "${BuildConfig.GIT_COMMIT} ${settingsState.sourceLabel}"
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                val message = if (capture.save(uri, build)) {
                    R.string.unified_diag_save_success
                } else {
                    R.string.unified_diag_save_failed
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.auto_diag_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.auto_diag_description), style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.auto_diag_enable), modifier = Modifier.weight(1f))
                Switch(checked = status.enabled, onCheckedChange = capture::enable, enabled = !clearing)
            }
            val current = status.recordingAttempt
            if (current != null) {
                Text(stringResource(R.string.auto_diag_recording, current))
            } else if (status.enabled) {
                Text(stringResource(R.string.auto_diag_waiting))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !clearing, onClick = {
                    save.launch(UnifiedDiagnosticsFileExport.androidAutoFilename(Instant.now()))
                }) {
                    Text(stringResource(R.string.unified_diag_save_json))
                }
                TextButton(enabled = !clearing, onClick = capture::clear) {
                    Text(stringResource(if (clearing) R.string.unified_diag_clearing else R.string.auto_diag_clear))
                }
            }
        }
    }
}
