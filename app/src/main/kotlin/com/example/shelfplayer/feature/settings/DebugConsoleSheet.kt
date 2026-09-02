package com.example.shelfplayer.feature.settings

import android.content.ClipData
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.BuildConfig
import com.example.shelfplayer.R
import kotlinx.coroutines.launch
import java.time.Instant

/** So a test can find the copy control without depending on its label. */
internal const val DEBUG_CONSOLE_COPY = "debug-console-copy"

/**
 * PRODUCT_SPEC 14.4 — everything this build knows about itself, as one copyable block.
 *
 * ### What it is for
 *
 * The owner tests on a device and reports back. Without this, "it did not work" arrives with no version, no
 * capability set, no counts and no log — and the next question is always the same three. This answers them
 * in one paste.
 *
 * ### Copy, not share, and not a file
 *
 * The same reasoning `EventLogSheet` records, and it holds harder here because this block is wider than the
 * log: a share sheet hands the text to an arbitrary app, and a file export would need a `FileProvider` this
 * manifest does not have. The clipboard keeps the decision with the person who pressed the button — they
 * choose where it goes, and they can read it first.
 *
 * ### What is not in it
 *
 * The server's address, library names, book titles and device names. [DiagnosticsReport] explains why and
 * `DiagnosticsReportTest` enforces it: the event log is redacted at the source, but `SettingsUiState` is
 * live domain state and would leak a household's shelf into a bug report.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugConsoleSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    logs: EventLogViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val metrics by viewModel.playbackMetrics.collectAsStateWithLifecycle()
    val events by logs.events.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    // Rebuilt when the inputs change rather than on every recomposition: this walks the whole event buffer,
    // and the sheet recomposes on every scroll.
    val report = remember(state, metrics, events) {
        DiagnosticsReport.of(
            // Everything needed to identify the build a pasted report came from, in one line: the
            // product version, the code that says which build it is, the type, and the branch and pull
            // request it was cut from. The About tab shows the same four facts across three rows.
            appVersion = state.versionLabel +
                " ${BuildConfig.BUILD_TYPE} ${BuildConfig.GIT_COMMIT} ${state.sourceLabel}",
            state = state,
            metrics = metrics,
            events = events,
            at = Instant.now(),
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxHeight(SHEET_HEIGHT).padding(horizontal = 24.dp)) {
            Text(
                text = stringResource(R.string.debug_console_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.debug_console_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            TextButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText(LABEL, AnnotatedString(report))),
                        )
                    }
                },
                modifier = Modifier.testTag(DEBUG_CONSOLE_COPY),
            ) {
                Text(text = stringResource(R.string.debug_console_copy))
            }
            Text(
                text = report,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                // Horizontal scroll rather than wrapping: this is a report to be read in columns, and a
                // wrapped `lastFailure:` line is harder to scan than one that runs off the edge.
                overflow = TextOverflow.Clip,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            )
        }
    }
}

/** What the clipboard entry calls itself in a paste preview. */
private const val LABEL = "BookWave diagnostics"

/** Nearly full screen. A diagnostics block at half height shows the header and nothing else. */
private const val SHEET_HEIGHT = 0.92f
