package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.playback.ColdResumeDiagnostic
import com.example.shelfplayer.playback.ColdResumeDiagnosticState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Issue #138 — debug-only About controls for deterministic cold MediaSession resumption testing. */
internal fun LazyListScope.playbackDiagnosticsRows() {
    item { PlaybackDiagnosticsSection() }
}

@Composable
private fun PlaybackDiagnosticsSection() {
    val context = LocalContext.current.applicationContext
    val diagnostic = remember(context) {
        EntryPointAccessors.fromApplication(context, PlaybackDiagnosticsEntryPoint::class.java)
            .coldResumeDiagnostic()
    }
    val state by diagnostic.state.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxWidth()) {
        SubHeader(text = stringResource(R.string.about_playback_diagnostics))
        Hint(text = stringResource(R.string.about_playback_diagnostics_body))
        Text(
            text = stringResource(R.string.about_playback_diagnostics_state, state.label()),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        TextButton(
            onClick = diagnostic::arm,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(text = stringResource(R.string.about_playback_diagnostics_arm_zero))
        }
    }
}

@Composable
private fun ColdResumeDiagnosticState.label(): String = stringResource(
    when (this) {
        ColdResumeDiagnosticState.NotArmed -> R.string.about_playback_diagnostics_not_armed
        ColdResumeDiagnosticState.Armed -> R.string.about_playback_diagnostics_armed
        ColdResumeDiagnosticState.Consumed -> R.string.about_playback_diagnostics_consumed
    },
)

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlaybackDiagnosticsEntryPoint {
    fun coldResumeDiagnostic(): ColdResumeDiagnostic
}
