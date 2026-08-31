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
 * ### It is shown whenever there is an output at all
 *
 * It used to hide below two outputs, on the reasoning that a menu offering the only thing available answers
 * nothing. A device run retired that: in a car the control did not appear at all, and the car *is* the
 * output somebody might want to move a book off. The count is the wrong question — the control is how a
 * listener finds out where the book is going, which is worth having even when there is one answer.
 *
 * ### Two facts, shown separately
 *
 * The **tick** is the choice: a device, or *Automatic*. The **"playing here"** label is where the platform
 * says the audio actually went, read from `AudioManager.getAudioDevicesForAttributes` on API 33+.
 *
 * They can disagree, and when they do that is the finding rather than a glitch to paper over: choosing the
 * phone speaker while a headset is connected leaves the sound in the headset on some devices, because
 * `setPreferredDevice` is a request the platform may decline and there is no public API to force it. Showing
 * the choice alone would be a tick that lies; showing the route alone would lose what was asked for.
 */
@Composable
internal fun AudioOutputAction(controls: OutputControls, modifier: Modifier = Modifier) {
    if (controls.outputs.isEmpty()) return
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
                isSelected = controls.selectedId == null,
                isRouted = false,
                onClick = {
                    controls.onSelect(null)
                    expanded = false
                },
            )
            controls.outputs.forEach { output ->
                OutputRow(
                    label = output.displayName,
                    icon = iconFor(output.kind),
                    isSelected = output.id == controls.selectedId,
                    isRouted = output.isActive,
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
private fun OutputRow(label: String, icon: ImageVector, isSelected: Boolean, isRouted: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = if (isRouted) stringResource(R.string.player_output_playing_here, label) else label,
            )
        },
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
