package com.example.shelfplayer.feature.settings

import android.content.ClipData
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.BuildConfig
import kotlinx.coroutines.launch

/** AudioTrack-level route evidence has its own destination, independent of the Debug Console. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioRouteLabSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    lab: AudioRouteLabViewModel = hiltViewModel(),
    settings: SettingsViewModel = hiltViewModel(),
) {
    val state by lab.state.collectAsStateWithLifecycle()
    val app by settings.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.92f).padding(horizontal = 24.dp)) {
            AudioRouteLabPanel(
                state = state,
                onStart = lab::start,
                onStop = lab::stop,
                onClear = lab::clear,
                onMark = lab::mark,
                onHeard = lab::heardAt,
                onCopy = {
                    scope.launch {
                        val version = app.versionLabel + " " + BuildConfig.BUILD_TYPE +
                            " " + BuildConfig.GIT_COMMIT + " " + app.sourceLabel
                        clipboard.setClipEntry(
                            ClipEntry(
                                ClipData.newPlainText(
                                    "BookWave Audio Route Lab",
                                    AnnotatedString(lab.report(version)),
                                ),
                            ),
                        )
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
