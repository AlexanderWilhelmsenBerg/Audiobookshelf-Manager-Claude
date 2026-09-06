package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.DevicePolicy
import com.example.shelfplayer.core.model.playback.KnownDevice

/** One remembered output, collapsed to its name and current connection policy. */
@Composable
fun DeviceRow(
    device: KnownDevice,
    onPolicyChanged: (DevicePolicy) -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsCard {
        ExpandableSettingsRow(
            label = device.displayName,
            valueLabel = stringResource(device.policy.label()),
            modifier = modifier,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
            ) {
                DevicePolicy.entries.forEach { policy ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = policy == device.policy,
                                role = Role.RadioButton,
                                onClick = { onPolicyChanged(policy) },
                            )
                            .padding(start = 32.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(policy.label()),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (policy == device.policy) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                ActionRow(
                    label = stringResource(R.string.settings_device_forget),
                    onClick = onForget,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}

private fun DevicePolicy.label(): Int = when (this) {
    DevicePolicy.Never -> R.string.settings_device_policy_never
    DevicePolicy.ArmOnly -> R.string.settings_device_policy_arm
    DevicePolicy.AutoPlay -> R.string.settings_device_policy_auto
    DevicePolicy.Ask -> R.string.settings_device_policy_ask
}
