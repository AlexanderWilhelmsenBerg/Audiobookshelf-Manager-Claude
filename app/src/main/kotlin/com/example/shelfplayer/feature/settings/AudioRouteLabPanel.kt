package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.shelfplayer.R

/** Optional listener observations for comparing physical input and measured output. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AudioRouteLabMarkers(enabled: Boolean, onMark: (String) -> Unit, onHeard: (String) -> Unit) {
    Text(stringResource(R.string.route_lab_heard_title), style = MaterialTheme.typography.titleSmall)
    FlowRow {
        Marker(enabled, R.string.route_lab_silence) { onHeard("Silence") }
        Marker(enabled, R.string.unified_diag_skipping) { onHeard("Skipping") }
        Marker(enabled, R.string.route_lab_car) { onHeard("Car") }
        Marker(enabled, R.string.route_lab_phone) { onHeard("Phone") }
        Marker(enabled, R.string.unified_diag_headset) { onHeard("Headset") }
    }
    FlowRow {
        Marker(enabled, R.string.route_lab_button_a) { onMark("ButtonA") }
        Marker(enabled, R.string.route_lab_button_b) { onMark("ButtonB") }
    }
}

@Composable
private fun Marker(enabled: Boolean, label: Int, onClick: () -> Unit) {
    TextButton(enabled = enabled, onClick = onClick) { Text(stringResource(label)) }
}
