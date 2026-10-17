package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.playback.AudioRouteLabState

internal const val AUDIO_ROUTE_LAB_START = "audio-route-lab-start"
internal const val AUDIO_ROUTE_LAB_REPORT = "audio-route-lab-report"

/**
 * A manual, stationary hardware test companion. Does not generate sound, route a player, change
 * audio focus or intercept media buttons. Observations are explicitly marked as listener reports.
 */
@Composable
internal fun AudioRouteLabPanel(
    state: AudioRouteLabState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onMark: (String) -> Unit,
    onHeard: (String) -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.route_lab_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.route_lab_explanation), style = MaterialTheme.typography.bodySmall)
        Text(
            if (state.recording) stringResource(R.string.route_lab_recording) else
                stringResource(R.string.route_lab_not_recording),
            style = MaterialTheme.typography.labelMedium,
        )
        Row {
            TextButton(
                onClick = if (state.recording) onStop else onStart,
                modifier = Modifier.testTag(AUDIO_ROUTE_LAB_START),
            ) {
                Text(
                    if (state.recording) stringResource(R.string.route_lab_stop) else
                        stringResource(R.string.route_lab_start),
                )
            }
            TextButton(onClick = onClear) { Text(stringResource(R.string.route_lab_clear)) }
            TextButton(
                onClick = onCopy,
                modifier = Modifier.testTag(AUDIO_ROUTE_LAB_REPORT),
            ) { Text(stringResource(R.string.route_lab_copy)) }
        }
        HorizontalDivider()
        Text(stringResource(R.string.route_lab_route_title), style = MaterialTheme.typography.titleSmall)
        Text(state.route, style = MaterialTheme.typography.bodySmall)
        Text(state.reportStatus, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(stringResource(R.string.route_lab_steps_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.route_lab_steps_help), style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(enabled = state.recording, onClick = { onMark("Baseline") }) {
                Text(stringResource(R.string.route_lab_baseline))
            }
            TextButton(enabled = state.recording, onClick = { onMark("CarBluetooth") }) {
                Text(stringResource(R.string.route_lab_car_bluetooth))
            }
        }
        Row {
            TextButton(enabled = state.recording, onClick = { onMark("AndroidAuto") }) {
                Text(stringResource(R.string.route_lab_auto))
            }
            TextButton(enabled = state.recording, onClick = { onMark("CarPlay") }) {
                Text(stringResource(R.string.route_lab_car_play))
            }
        }
        Row {
            TextButton(enabled = state.recording, onClick = { onMark("ButtonA") }) {
                Text(stringResource(R.string.route_lab_button_a))
            }
            TextButton(enabled = state.recording, onClick = { onMark("ButtonB") }) {
                Text(stringResource(R.string.route_lab_button_b))
            }
        }
        Text(stringResource(R.string.route_lab_heard_title), style = MaterialTheme.typography.titleSmall)
        Row {
            TextButton(enabled = state.recording, onClick = { onHeard("HeadsetA") }) { Text("A") }
            TextButton(enabled = state.recording, onClick = { onHeard("HeadsetB") }) { Text("B") }
            TextButton(enabled = state.recording, onClick = { onHeard("Car") }) {
                Text(stringResource(R.string.route_lab_car))
            }
            TextButton(enabled = state.recording, onClick = { onHeard("Silence") }) {
                Text(stringResource(R.string.route_lab_silence))
            }
        }
        Row {
            TextButton(enabled = state.recording, onClick = { onHeard("Phone") }) {
                Text(stringResource(R.string.route_lab_phone))
            }
        }
        HorizontalDivider()
        Text(stringResource(R.string.route_lab_latest), style = MaterialTheme.typography.titleSmall)
        state.entries.takeLast(16).forEach { record ->
            Text(
                record,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
    }
}
