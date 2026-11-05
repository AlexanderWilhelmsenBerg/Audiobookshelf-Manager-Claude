package com.example.shelfplayer.feature.player

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.BluetoothIdentityCapability

/** Phone-only opt-in. Permission and discovery never issue Play or change the selected output. */
@Composable
internal fun HeadsetContinuityAccess(controls: OutputControls) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val context = LocalContext.current
    var explain by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        controls.onRefreshIdentityAccess()
        explain = !it
    }
    LifecycleResumeEffect(Unit) {
        controls.onRefreshIdentityAccess()
        onPauseOrDispose { }
    }
    val label = when (controls.identityCapability) {
        BluetoothIdentityCapability.PermissionMissing -> R.string.headset_continuity_enable

        BluetoothIdentityCapability.Verified -> R.string.headset_continuity_available

        BluetoothIdentityCapability.Redacted,
        BluetoothIdentityCapability.Empty,
        BluetoothIdentityCapability.Invalid,
        BluetoothIdentityCapability.Duplicate,
        BluetoothIdentityCapability.ReadFailure,
        BluetoothIdentityCapability.Unsupported,
        -> R.string.headset_continuity_waiting
    }
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { explain = true })
    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text(stringResource(R.string.headset_continuity_enable)) },
            text = { Text(stringResource(R.string.headset_continuity_explanation)) },
            confirmButton = {
                TextButton(onClick = {
                    explain = false
                    if (controls.identityCapability == BluetoothIdentityCapability.PermissionMissing) {
                        launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                }) { Text(stringResource(R.string.headset_continuity_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    explain = false
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:${context.packageName}".toUri(),
                        ),
                    )
                }) { Text(stringResource(R.string.headset_continuity_settings)) }
            },
        )
    }
}
