package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.shelfplayer.ui.glass.GlassCard

/** SET-002: existing glass tokens, bounded body and reachable dialog actions. */
@Composable
internal fun SettingsGlassDialog(
    title: String,
    close: () -> Unit,
    closeLabel: String,
    confirmLabel: String? = null,
    confirmEnabled: Boolean = true,
    confirm: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GlassCard(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp).testTag("settings-glass-dialog"),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f, fill = false).heightIn(max = 400.dp), content = content)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = close) { Text(closeLabel) }
                    confirmLabel?.let { TextButton(onClick = confirm, enabled = confirmEnabled) { Text(it) } }
                }
            }
        }
    }
}
