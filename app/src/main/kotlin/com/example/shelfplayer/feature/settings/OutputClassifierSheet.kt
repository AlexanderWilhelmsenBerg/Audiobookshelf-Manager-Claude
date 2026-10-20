package com.example.shelfplayer.feature.settings

import android.content.ClipData
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.BuildConfig
import com.example.shelfplayer.R
import kotlinx.coroutines.launch

/** Distinct from the general event log and the AudioTrack Route Lab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutputClassifierSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    classifier: OutputClassifierViewModel = hiltViewModel(),
    settings: SettingsViewModel = hiltViewModel(),
) {
    val state by classifier.state.collectAsStateWithLifecycle()
    val app by settings.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxHeight(0.92f).padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.output_classifier_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.output_classifier_explanation), style = MaterialTheme.typography.bodySmall)
            Text(state.status, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = if (state.recording) classifier::stop else classifier::start) {
                    Text(
                        stringResource(
                            if (state.recording) R.string.route_lab_stop else R.string.route_lab_start,
                        ),
                    )
                }
                TextButton(onClick = classifier::refresh, enabled = state.recording) {
                    Text(stringResource(R.string.output_classifier_refresh))
                }
                TextButton(onClick = classifier::clear) { Text(stringResource(R.string.route_lab_clear)) }
            }
            TextButton(
                onClick = {
                    scope.launch {
                        val version = app.versionLabel + " " + BuildConfig.BUILD_TYPE +
                            " " + BuildConfig.GIT_COMMIT + " " + app.sourceLabel
                        clipboard.setClipEntry(
                            ClipEntry(
                                ClipData.newPlainText(
                                    "BookWave Output Classifier",
                                    AnnotatedString(classifier.report(version)),
                                ),
                            ),
                        )
                    }
                },
            ) { Text(stringResource(R.string.output_classifier_copy)) }
            Text(stringResource(R.string.output_classifier_connected), style = MaterialTheme.typography.titleSmall)
            state.current.forEach { row ->
                Text(row, style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.output_classifier_history), style = MaterialTheme.typography.titleSmall)
            state.entries.takeLast(80).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
