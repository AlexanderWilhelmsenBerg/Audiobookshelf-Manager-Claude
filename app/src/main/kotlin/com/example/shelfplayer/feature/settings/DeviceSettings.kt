package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.core.model.playback.DevicePolicy
import com.example.shelfplayer.core.model.playback.KnownDevice

/**
 * PRODUCT_SPEC ROUTE-002 / SET-002 (Devices) — one row per device this app has seen.
 *
 * ### Four chips rather than a menu
 *
 * The four policies are short, mutually exclusive, and the whole reason somebody opened this screen. A
 * dropdown would hide three of them behind a tap and make the difference between *Arm only* and *Auto-play*
 * — which is the difference between silence and sound — something you have to go looking for.
 *
 * ### The list only ever grows by connecting something
 *
 * There is no "add a device" button, because the app cannot invent one: a device appears the first time it
 * connects, with `Arm only`. The empty state says that, so a screen with nothing on it reads as *"plug
 * something in"* rather than as broken.
 */
@Composable
fun DeviceRow(
    device: KnownDevice,
    onPolicyChanged: (DevicePolicy) -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(device.kind.label()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onForget) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.settings_device_forget),
                )
            }
        }
        Row(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DevicePolicy.entries.forEach { policy ->
                FilterChip(
                    selected = policy == device.policy,
                    onClick = { onPolicyChanged(policy) },
                    label = { Text(text = stringResource(policy.label())) },
                    modifier = Modifier.selectable(
                        selected = policy == device.policy,
                        role = Role.RadioButton,
                        onClick = { onPolicyChanged(policy) },
                    ),
                )
            }
        }
        // ROUTE-002: "Auto-play never starts explicit content when the device is classified as a speaker
        // unless separately confirmed", and the honest place to say so is beside the choice that causes it.
        if (device.isSpeaker && device.policy == DevicePolicy.AutoPlay) {
            Text(
                text = stringResource(R.string.settings_device_speaker_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun DeviceKind.label(): Int = when (this) {
    DeviceKind.Wired -> R.string.settings_device_kind_wired
    DeviceKind.Bluetooth -> R.string.settings_device_kind_bluetooth
    DeviceKind.Car -> R.string.settings_device_kind_car
    DeviceKind.HearingAid -> R.string.settings_device_kind_hearing_aid
    DeviceKind.Speaker -> R.string.settings_device_kind_speaker
    DeviceKind.Other -> R.string.settings_device_kind_other
}

private fun DevicePolicy.label(): Int = when (this) {
    DevicePolicy.Never -> R.string.settings_device_policy_never
    DevicePolicy.ArmOnly -> R.string.settings_device_policy_arm
    DevicePolicy.AutoPlay -> R.string.settings_device_policy_auto
    DevicePolicy.Ask -> R.string.settings_device_policy_ask
}
