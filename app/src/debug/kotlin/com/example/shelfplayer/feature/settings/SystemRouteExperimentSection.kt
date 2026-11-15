package com.example.shelfplayer.feature.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.shelfplayer.R
import com.example.shelfplayer.playback.SystemRouteExperimentState
import com.example.shelfplayer.playback.SystemRouteStatus

private val startableStatuses = setOf(
    SystemRouteStatus.Closed,
    SystemRouteStatus.PermissionRequired,
    SystemRouteStatus.Unavailable,
)

/** User-driven diagnostic only. Dismissal unregisters observers and never starts playback. */
@Composable
internal fun SystemRouteExperimentSection(viewModel: SystemRouteExperimentViewModel = hiltViewModel()) {
    val experiment = viewModel.experiment
    val state by experiment.state.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        experiment.open()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(experiment, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) experiment.close()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            experiment.close()
        }
    }
    SystemRouteExperimentControls(
        state = state,
        onToggle = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                state.status in startableStatuses
            ) {
                permission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
            } else {
                experiment.close()
            }
        },
        onTransfer = experiment::transfer,
    )
}

@Composable
internal fun SystemRouteExperimentControls(
    state: SystemRouteExperimentState,
    onToggle: () -> Unit,
    onTransfer: (String) -> Unit,
) {
    Text(stringResource(R.string.system_route_title))
    Text(stringResource(R.string.system_route_explanation))
    Text(stringResource(state.status.label()))
    TextButton(
        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        onClick = onToggle,
        modifier = Modifier.testTag("system-route-toggle"),
    ) {
        Text(
            stringResource(
                if (state.status in startableStatuses) {
                    R.string.system_route_enable
                } else {
                    R.string.system_route_disable
                },
            ),
        )
    }
    if (state.status == SystemRouteStatus.Observing && state.routes.isEmpty()) {
        Text(stringResource(R.string.system_route_empty))
    }
    state.routes.forEach { route ->
        Text("${route.alias}: ${route.displayName}")
        TextButton(
            enabled = !route.selected && !state.attempted,
            onClick = { onTransfer(route.alias) },
            modifier = Modifier.testTag("system-route-${route.alias}"),
        ) {
            Text(
                if (route.selected) {
                    stringResource(R.string.system_route_current)
                } else {
                    stringResource(R.string.system_route_transfer, route.displayName)
                },
            )
        }
    }
}

private fun SystemRouteStatus.label(): Int = when (this) {
    SystemRouteStatus.Closed -> R.string.system_route_closed
    SystemRouteStatus.Unsupported -> R.string.system_route_unsupported
    SystemRouteStatus.PermissionRequired -> R.string.system_route_permission
    SystemRouteStatus.Observing -> R.string.system_route_observing
    SystemRouteStatus.Requested -> R.string.system_route_requested
    SystemRouteStatus.Selected -> R.string.system_route_selected
    SystemRouteStatus.Failed -> R.string.system_route_failed
    SystemRouteStatus.TimedOut -> R.string.system_route_timeout
    SystemRouteStatus.Unavailable -> R.string.system_route_unavailable
    SystemRouteStatus.CallBlocked -> R.string.system_route_call_blocked
}
