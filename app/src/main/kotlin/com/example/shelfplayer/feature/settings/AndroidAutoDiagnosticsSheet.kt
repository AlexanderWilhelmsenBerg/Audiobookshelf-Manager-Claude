package com.example.shelfplayer.feature.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
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
    val drives by capture.drives.collectAsStateWithLifecycle()
    val clearing by capture.clearing.collectAsStateWithLifecycle()
    val settingsState by settings.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    var pendingFile by rememberSaveable { mutableStateOf<String?>(null) }
    val build = "${settingsState.versionLabel} ${BuildConfig.BUILD_TYPE} " +
        "${BuildConfig.GIT_COMMIT} ${settingsState.sourceLabel}"
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val source = pendingFile?.toUri()
        pendingFile = null
        if (uri != null && source != null) {
            scope.launch {
                val message = if (capture.save(uri, source)) {
                    R.string.unified_diag_save_success
                } else {
                    R.string.unified_diag_save_failed
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
    fun export(share: Boolean, ids: Set<Long>) {
        if (preparing || ids.isEmpty()) return
        preparing = true
        scope.launch {
            try {
                val uri = capture.prepare(build, ids)
                if (uri == null) {
                    Toast.makeText(context, R.string.auto_drive_unavailable, Toast.LENGTH_LONG).show()
                } else if (share) {
                    context.startActivity(driveShareIntent(uri))
                } else {
                    pendingFile = uri.toString()
                    save.launch(UnifiedDiagnosticsFileExport.androidAutoFilename(Instant.now()))
                }
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, R.string.auto_drive_unavailable, Toast.LENGTH_LONG).show()
            } finally {
                preparing = false
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        DriveHistoryPicker(
            drives,
            status,
            clearing,
            preparing,
            onEnable = capture::enable,
            onClear = capture::clear,
            onExport = ::export,
        )
    }
}

internal fun driveShareIntent(uri: Uri): Intent = Intent.createChooser(
    Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("BookWave drive diagnostics", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    },
    null,
)
