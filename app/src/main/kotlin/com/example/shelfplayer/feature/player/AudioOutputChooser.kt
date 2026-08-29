package com.example.shelfplayer.feature.player

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.DeviceKind

/**
 * PRODUCT_SPEC PLAY-002 — the output chooser on the player card.
 *
 * ### Why it is not shown when there is one output
 *
 * A phone always reports its own speaker, so "no outputs" is not a state a listener sees; "one output" is,
 * and it is a menu offering the thing already happening. The control appears exactly when there is a choice
 * to make, which is the same rule the car's tab uses — one behaviour to learn rather than two.
 *
 * ### What the tick means, and what it does not
 *
 * It marks what the player was **asked** for, not where sound is provably coming from. Android exposes no
 * way to ask "which output is media using"; `setPreferredAudioDevice` is a preference the platform honours
 * while it can. So *Automatic* is ticked whenever nothing has been chosen — including when the system has
 * sensibly routed to a headset — because claiming to know more than that would be a lie a listener could
 * catch.
 */
@Composable
internal fun AudioOutputAction(controls: OutputControls, modifier: Modifier = Modifier) {
    if (controls.outputs.size < 2) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val active = controls.outputs.firstOrNull(AudioOutput::isActive)
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.BluetoothAudio,
                // Names the current destination rather than the control, so a screen reader announces where
                // the book is going instead of "button".
                contentDescription = stringResource(
                    R.string.player_output_current,
                    active?.displayName ?: stringResource(R.string.player_output_automatic),
                ),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            OutputRow(
                label = stringResource(R.string.player_output_automatic),
                icon = Icons.Filled.Audiotrack,
                isSelected = active == null,
                onClick = {
                    controls.onSelect(null)
                    expanded = false
                },
            )
            controls.outputs.forEach { output ->
                OutputRow(
                    label = output.displayName,
                    icon = iconFor(output.kind),
                    isSelected = output.isActive,
                    onClick = {
                        controls.onSelect(output.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun OutputRow(label: String, icon: ImageVector, isSelected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
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
