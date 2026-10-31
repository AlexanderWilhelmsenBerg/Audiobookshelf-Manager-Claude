package com.example.shelfplayer.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputLockStatus
import com.example.shelfplayer.core.model.playback.AudioOutputPresentation
import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.core.model.playback.OutputDestination
import com.example.shelfplayer.core.model.playback.OutputMeasurement
import com.example.shelfplayer.core.model.playback.OutputTransferStatus

/**
 * PRODUCT_SPEC PLAY-002 — the output chooser on the player card.
 *
 * ### It remains available with no listed endpoints
 *
 * Automatic and Car are intent commands, so an empty phone endpoint list must not hide them. The menu also
 * reports the current measurement and transfer state when there are no mapped endpoints.
 *
 * ### Two facts, shown separately
 *
 * The tick is the request: a device, *Car*, or *Automatic*. The measured-output label comes only from the
 * current AudioTrack observation. Framework media policy is a prediction and is never presented as measured.
 *
 * They can disagree, and when they do that is the finding rather than a glitch to paper over: choosing the
 * phone speaker while a headset is connected leaves the sound in the headset on some devices, because
 * `setPreferredDevice` is a request the platform may decline and there is no public API to force it. Showing
 * the choice alone would be a tick that lies; showing the route alone would lose what was asked for.
 */
@Composable
internal fun AudioOutputAction(
    controls: OutputControls,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var lockRefused by remember(expanded) { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutputChooserButton(selectedLabel = selectedOutputLabel(controls)) {
            onExpandedChange(true)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            RequestedDestinationRows(controls, onExpandedChange)
            OutputMeasurementStatus(controls.presentation, measuredOutput(controls))
            OutputTransferStatusRow(controls.presentation)
            EndpointOutputRows(controls, onExpandedChange)
            OutputLockControls(controls, lockRefused, onLockRefused = { lockRefused = true }) {
                onExpandedChange(false)
            }
        }
    }
}

@Composable
private fun OutputChooserButton(selectedLabel: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Filled.BluetoothAudio,
            contentDescription = stringResource(R.string.player_output_current, selectedLabel),
        )
    }
}

@Composable
private fun selectedOutputLabel(controls: OutputControls): String {
    val presentation = controls.presentation
    return when (presentation?.requested) {
        OutputDestination.Car -> stringResource(R.string.player_output_car)

        OutputDestination.Automatic, null -> stringResource(R.string.player_output_automatic)

        OutputDestination.Output ->
            controls.outputs
                .singleOrNull { it.id == presentation.requestedOutputId }
                ?.displayName ?: stringResource(R.string.player_output_selected_device)
    }
}

private fun measuredOutput(controls: OutputControls) = controls.presentation?.measuredOutputId?.let { id ->
    controls.outputs.singleOrNull { it.id == id }
}

@Composable
private fun RequestedDestinationRows(controls: OutputControls, onExpandedChange: (Boolean) -> Unit) {
    val presentation = controls.presentation
    OutputRow(
        label = stringResource(R.string.player_output_automatic),
        icon = Icons.Filled.Audiotrack,
        isSelected = if (presentation == null) {
            controls.selectedId == null
        } else {
            presentation.requested == null || presentation.requested == OutputDestination.Automatic
        },
        isRouted = false,
        onClick = {
            controls.onSelect(null)
            onExpandedChange(false)
        },
    )
    OutputRow(
        label = stringResource(R.string.player_output_car),
        icon = Icons.Filled.DirectionsCar,
        isSelected = presentation?.requested == OutputDestination.Car,
        isRouted = false,
        onClick = {
            controls.onSelectCar()
            onExpandedChange(false)
        },
    )
}

@Composable
private fun OutputMeasurementStatus(presentation: AudioOutputPresentation?, measuredOutput: AudioOutput?) {
    if (presentation == null) return
    val label = when (presentation.measurement) {
        OutputMeasurement.RemoteSubmix -> stringResource(R.string.player_output_remote_submix)

        OutputMeasurement.Unresolved -> stringResource(R.string.player_output_unresolved)

        OutputMeasurement.Multiple -> stringResource(R.string.player_output_multiple)

        OutputMeasurement.Unavailable -> stringResource(R.string.player_output_unavailable)

        OutputMeasurement.Endpoint -> if (measuredOutput == null) {
            stringResource(R.string.player_output_unresolved)
        } else {
            null
        }
    }
    if (label != null) OutputStatus(label)
}

@Composable
private fun OutputTransferStatusRow(presentation: AudioOutputPresentation?) {
    val transfer = presentation?.transfer ?: return
    val label = when (transfer) {
        OutputTransferStatus.Waiting -> stringResource(R.string.player_output_transfer_waiting)
        OutputTransferStatus.Verifying -> stringResource(R.string.player_output_transfer_verifying)
        OutputTransferStatus.Verified -> stringResource(R.string.player_output_transfer_verified)
        OutputTransferStatus.Delegated -> stringResource(R.string.player_output_delegated)
        OutputTransferStatus.Failed -> stringResource(R.string.player_output_transfer_failed)
    }
    OutputStatus(label, announce = true)
}

@Composable
private fun EndpointOutputRows(controls: OutputControls, onExpandedChange: (Boolean) -> Unit) {
    val presentation = controls.presentation
    val measuredOutput = measuredOutput(controls)
    controls.outputs.forEach { output ->
        OutputRow(
            label = output.displayName,
            icon = iconFor(output.kind),
            isSelected = if (presentation == null) {
                output.id == controls.selectedId
            } else {
                presentation.requested == OutputDestination.Output &&
                    output.id == presentation.requestedOutputId
            },
            isRouted = presentation != null && output.id == measuredOutput?.id &&
                presentation.measurement == OutputMeasurement.Endpoint,
            onClick = {
                controls.onSelect(output.id)
                onExpandedChange(false)
            },
        )
    }
}

@Composable
private fun OutputLockControls(
    controls: OutputControls,
    lockRefused: Boolean,
    onLockRefused: () -> Unit,
    onClose: () -> Unit,
) {
    when {
        controls.lockStatus == null && controls.canKeepAudioHere -> KeepAudioHereRow(controls, onLockRefused, onClose)
        controls.lockStatus != null -> ReleaseOutputLockRows(controls, onClose)
    }
    if (lockRefused) {
        OutputStatus(stringResource(R.string.player_output_lock_refused), announce = true)
    }
}

@Composable
private fun KeepAudioHereRow(controls: OutputControls, onLockRefused: () -> Unit, onClose: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.player_output_keep_here)) },
        onClick = { if (controls.onKeepAudioHere()) onClose() else onLockRefused() },
    )
}

@Composable
private fun ReleaseOutputLockRows(controls: OutputControls, onClose: () -> Unit) {
    val statusLabel = if (controls.presentation?.hasRetainedPreviousLock == true) {
        stringResource(R.string.player_output_previous_retained)
    } else {
        when (controls.lockStatus) {
            AudioOutputLockStatus.Active -> stringResource(R.string.player_output_kept_here)
            AudioOutputLockStatus.Waiting -> stringResource(R.string.player_output_waiting_here)
            AudioOutputLockStatus.Unverified -> stringResource(R.string.player_output_unverified_here)
            null -> return
        }
    }
    OutputStatus(statusLabel)
    DropdownMenuItem(
        text = { Text(stringResource(R.string.player_output_release_lock)) },
        onClick = {
            controls.onReleaseLock()
            onClose()
        },
    )
}

@Composable
private fun OutputStatus(label: String, announce: Boolean = false) {
    Text(
        text = label,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics {
            if (announce) liveRegion = LiveRegionMode.Polite
        },
    )
}

@Composable
private fun OutputRow(label: String, icon: ImageVector, isSelected: Boolean, isRouted: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = if (isRouted) stringResource(R.string.player_output_playing_here, label) else label,
            )
        },
        onClick = onClick,
        modifier = Modifier.semantics { selected = isSelected },
        leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
        trailingIcon = {
            // PRODUCT_SPEC 21 — the tick is drawn *and* announced. Colour alone may not carry a state, and
            // a checkmark with no description is a decoration to a screen reader.
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.player_output_selected),
                )
            }
        },
    )
}

/** The glyph for a kind. The same six [DeviceKind]s ROUTE-002's settings list draws. */
private fun iconFor(kind: DeviceKind): ImageVector = when (kind) {
    DeviceKind.Bluetooth -> Icons.Filled.BluetoothAudio
    DeviceKind.Wired -> Icons.Filled.Headset
    DeviceKind.Speaker -> Icons.Filled.Speaker
    DeviceKind.Car -> Icons.Filled.DirectionsCar
    DeviceKind.HearingAid -> Icons.Filled.Hearing
    DeviceKind.Other -> Icons.Filled.Audiotrack
}
