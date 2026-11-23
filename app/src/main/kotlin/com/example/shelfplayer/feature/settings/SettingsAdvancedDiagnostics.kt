package com.example.shelfplayer.feature.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.example.shelfplayer.BuildConfig
import com.example.shelfplayer.R

/** SET-002 and #180: diagnostics are available from About when needed. */
@Composable
internal fun SettingsAdvancedDiagnostics(recovery: RecoveryTestInputs) {
    var openTool by rememberSaveable { mutableStateOf<DiagnosticTool?>(null) }

    ExpandableSettingsRow(label = stringResource(R.string.about_advanced_diagnostics)) {
        for (tool in DiagnosticTool.entries) {
            if (tool.isAvailable) {
                ActionRow(label = stringResource(tool.labelRes), onClick = { openTool = tool })
            }
        }
        if (BuildConfig.DEBUG) AuthenticationRecoveryTest(recovery)
    }

    val onDismiss = { openTool = null }
    when (openTool?.takeIf { it.isAvailable }) {
        DiagnosticTool.Playback -> UnifiedDiagnosticsSheet(onDismiss = onDismiss)
        DiagnosticTool.AndroidAuto -> AndroidAutoDiagnosticsSheet(onDismiss = onDismiss)
        DiagnosticTool.EventLog -> EventLogSheet(onDismiss = onDismiss)
        DiagnosticTool.DebugConsole -> DebugConsoleSheet(onDismiss = onDismiss)
        null -> Unit
    }
}

private enum class DiagnosticTool(@param:StringRes val labelRes: Int, private val debugOnly: Boolean = false) {
    Playback(R.string.unified_diag_open),
    AndroidAuto(R.string.auto_diag_title, debugOnly = true),
    EventLog(R.string.event_log_open),
    DebugConsole(R.string.debug_console_open),
    ;

    val isAvailable: Boolean get() = !debugOnly || BuildConfig.DEBUG
}

/** AUTH-004: invalidate the active profile's access token to exercise a real renewal. */
@Composable
private fun AuthenticationRecoveryTest(inputs: RecoveryTestInputs) {
    SubHeader(text = stringResource(R.string.about_recovery_test))
    Hint(text = stringResource(R.string.about_recovery_test_body))
    ActionRow(label = stringResource(R.string.about_recovery_test_expire), onClick = inputs.onExpireAccessToken)
    inputs.message?.let { message ->
        Hint(text = message)
        DisposableEffect(message) { onDispose(inputs.onMessageShown) }
    }
}
