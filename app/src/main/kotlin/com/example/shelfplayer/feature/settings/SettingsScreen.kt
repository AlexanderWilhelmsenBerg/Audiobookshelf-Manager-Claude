package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.playback.DevicePolicy
import com.example.shelfplayer.core.model.playback.KnownDevice
import com.example.shelfplayer.core.model.playback.PlaybackMetrics
import com.example.shelfplayer.launcher.LauncherIcon
import java.util.Locale
import kotlin.time.Duration

@Composable
fun SettingsRoute(
    onNavigateUp: () -> Unit,
    onManageDownloads: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val launcherIcon by viewModel.launcherIcon.collectAsStateWithLifecycle()
    val knownDevices by viewModel.knownDevices.collectAsStateWithLifecycle()
    val metrics by viewModel.playbackMetrics.collectAsStateWithLifecycle()
    SettingsScreen(
        uiState = uiState,
        launcherIcon = launcherIcon,
        onLauncherIconChanged = viewModel::onLauncherIconChanged,
        metrics = metrics,
        devices = DeviceSettingsActions(
            known = knownDevices,
            onPolicyChanged = viewModel::onDevicePolicyChanged,
            onForget = viewModel::onDeviceForgotten,
        ),
        onDefaultLibraryChanged = viewModel::onDefaultLibraryChanged,
        sleepTimerActions = SleepTimerSettingsActions(
            onDefaultChanged = viewModel::onSleepTimerDefaultChanged,
            onFadeChanged = viewModel::onSleepTimerFadeChanged,
            onShakeChanged = viewModel::onShakeToRestartChanged,
            onRewindOnStopChanged = viewModel::onSleepTimerRewindChanged,
        ),
        playbackActions = PlaybackSettingsActions(
            onSpeedChanged = viewModel::onDefaultSpeedChanged,
            onSkipsChanged = viewModel::onSkipIntervalsChanged,
            onAutoRewindChanged = viewModel::onAutoRewindChanged,
            onBufferChanged = viewModel::onBufferPresetChanged,
            onAutoPlayChanged = viewModel::onAutoPlayOnCarConnectChanged,
            onNetworkPolicyChanged = viewModel::onNetworkPolicyChanged,
            onHousekeepingChanged = viewModel::onHousekeepingChanged,
            onFocusBehaviourChanged = viewModel::onFocusBehaviourChanged,
            onStartupModeChanged = viewModel::onStartupModeChanged,
            onManageDownloads = onManageDownloads,
        ),
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )
}

/**
 * PRODUCT_SPEC SET-001 / SET-002 — two tabs: what the app is connected to, and what it knows about
 * itself.
 *
 * Tabs rather than a list of sections with a row that opens another screen. The two halves are read for
 * different reasons and neither is long enough to deserve a destination of its own; a tab switch keeps
 * both one gesture away, which a pushed screen does not.
 *
 * The selection is `rememberSaveable`, so a rotation or a trip through the background comes back to the
 * tab the user was on. Losing it would be a small thing that reads as the screen restarting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onDefaultLibraryChanged: (LibraryId?) -> Unit,
    sleepTimerActions: SleepTimerSettingsActions,
    playbackActions: PlaybackSettingsActions,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    launcherIcon: LauncherIcon = LauncherIcon.Default,
    onLauncherIconChanged: (LauncherIcon) -> Unit = {},
    devices: DeviceSettingsActions = DeviceSettingsActions(),
    metrics: PlaybackMetrics = PlaybackMetrics.Empty,
) {
    var selected by rememberSaveable { mutableStateOf(SettingsTab.Server) }
    // PRODUCT_SPEC 14.4 — the event log's open/closed state is this screen's, not the caller's. Lifting it
    // would add a parameter and a callback to a signature that is already at detekt's limit, to describe a
    // sheet nothing outside this screen can open.
    var isEventLogOpen by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            TabRow(selectedTabIndex = selected.ordinal) {
                SettingsTab.entries.forEach { tab ->
                    Tab(
                        selected = tab == selected,
                        onClick = { selected = tab },
                        text = { Text(text = stringResource(tab.labelRes)) },
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                when (selected) {
                    SettingsTab.Server -> serverTab(uiState, onDefaultLibraryChanged)
                    SettingsTab.Sleep -> sleepTimerTab(
                        settings = uiState.sleepTimer,
                        history = uiState.sleepTimerHistory,
                        actions = sleepTimerActions,
                    )

                    SettingsTab.Playback -> {
                        playbackTab(
                            settings = uiState.playback,
                            libraries = uiState.libraries,
                            actions = playbackActions,
                            networkPolicy = uiState.networkPolicy,
                            housekeeping = uiState.housekeeping,
                        )
                        devicesSection(devices)
                    }

                    SettingsTab.About -> aboutTab(
                        uiState = uiState,
                        launcherIcon = launcherIcon,
                        onLauncherIconChanged = onLauncherIconChanged,
                        metrics = metrics,
                        onOpenEventLog = { isEventLogOpen = true },
                    )
                }
            }
        }
    }

    if (isEventLogOpen) EventLogSheet(onDismiss = { isEventLogOpen = false })
}

/** PRODUCT_SPEC SET-002 — which server, and which of its libraries. */
private fun LazyListScope.serverTab(uiState: SettingsUiState, onDefaultLibraryChanged: (LibraryId?) -> Unit) {
    item { SectionHeader(text = stringResource(R.string.settings_section_server)) }
    uiState.server?.let { server -> serverInfoRows(server) }

    item { SectionHeader(text = stringResource(R.string.settings_section_libraries)) }
    if (uiState.libraries.isEmpty()) {
        item { Hint(text = stringResource(R.string.settings_libraries_empty)) }
    } else {
        items(uiState.libraries, key = { it.id.value }) { library ->
            LibraryRow(
                library = library,
                isDefault = library.id == uiState.defaultLibraryId,
                onToggled = { isDefault -> onDefaultLibraryChanged(library.id.takeIf { isDefault }) },
            )
        }
        item { Hint(text = stringResource(R.string.settings_default_library_hint)) }
    }
}

/**
 * PRODUCT_SPEC SET-002 — the app's own version, and the readings.
 *
 * Everything below the Testing heading exists to make an acceptance case checkable on a device rather
 * than through `adb`. It is deliberately grouped and deliberately labelled as such: these are numbers to
 * verify a build against, not settings to change.
 */
private fun LazyListScope.aboutTab(
    uiState: SettingsUiState,
    launcherIcon: LauncherIcon,
    onLauncherIconChanged: (LauncherIcon) -> Unit,
    metrics: PlaybackMetrics,
    onOpenEventLog: () -> Unit,
) {
    item { SectionHeader(text = stringResource(R.string.about_section_app)) }
    item { TextRow(labelRes = R.string.about_version, value = uiState.versionName) }
    item { Hint(text = stringResource(R.string.about_phase)) }

    // PRODUCT_SPEC SET-003 — on the About tab because it is about the app's own identity rather than
    // about a server, a book or how playback behaves.
    item { SectionHeader(text = stringResource(R.string.settings_section_icon)) }
    item { LauncherIconPicker(selected = launcherIcon, onSelected = onLauncherIconChanged) }
    item { Hint(text = stringResource(R.string.settings_icon_hint)) }

    // PRODUCT_SPEC 14.4 — before the readings, because this is the one thing on the tab somebody reaches
    // for while something is wrong rather than while checking a build.
    item { SectionHeader(text = stringResource(R.string.about_section_diagnostics)) }
    item { Hint(text = stringResource(R.string.event_log_body)) }
    item {
        TextButton(onClick = onOpenEventLog, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(text = stringResource(R.string.event_log_open))
        }
    }

    // PRODUCT_SPEC PLAY-006 — beside the diagnostics rather than under Testing, because these are
    // readings a listener uses to choose a buffer preset, not numbers to verify a build against.
    item { SectionHeader(text = stringResource(R.string.about_section_playback_metrics)) }
    playbackMetricsRows(metrics)

    item { SectionHeader(text = stringResource(R.string.about_section_testing)) }
    item { Hint(text = stringResource(R.string.about_testing_body)) }

    uiState.server?.let { server -> capabilityRows(server) }

    item { SubHeader(text = stringResource(R.string.settings_section_storage)) }
    item { Hint(text = stringResource(R.string.settings_storage_body)) }
    if (uiState.isLoaded) {
        storageRows(uiState.storage)
    } else {
        item { Hint(text = stringResource(R.string.settings_storage_loading)) }
    }

    // PRODUCT_SPEC PLAY-004 / PLAY-005 — the outbox's readings, then the checks they exist to settle. In
    // that order: a checklist above the numbers it is judged by would send the reader scrolling.
    if (uiState.isLoaded) {
        item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        notificationRows(uiState.notifications)
        // PRODUCT_SPEC PLAY-001 / ROUTE-002 — beside the notification block because it answers the same
        // shape of question: a control surface the app cannot see, and the reasons it might not be there.
        carRows(uiState.car)
        sessionSyncRows(uiState.sessionSync)
        syncCheckRows(uiState.sessionSync, uiState.notifications, uiState.car)
    }
}

/**
 * PRODUCT_SPEC 6.1 step 9 — a library row is a toggle, not a destination.
 *
 * There is no library screen to open: browsing happens on the shelf, and tapping here narrows it. The
 * star says which library the app opens on, and tapping the starred one again clears the choice.
 */
@Composable
private fun LibraryRow(
    library: Library,
    isDefault: Boolean,
    onToggled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = isDefault, role = Role.Checkbox, onValueChange = onToggled)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = library.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = pluralStringResource(R.plurals.home_library_books, library.bookCount, library.bookCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (isDefault) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = null,
            tint = if (isDefault) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * The tabs, in the order they appear. `ordinal` is the selected index, so the order is the order.
 *
 * Sleep sits between them deliberately: it is the only tab with a preference somebody changes more than
 * once, and About is a place you arrive at rather than pass through.
 */
/**
 * PRODUCT_SPEC ROUTE-002 — what happens when each known device connects.
 *
 * On the Playback tab rather than a tab of its own: it is a question about how playback *starts*, and it
 * sits directly under the auto-play switch it supersedes for any device listed here.
 */
private fun LazyListScope.devicesSection(devices: DeviceSettingsActions) {
    item { SectionHeader(text = stringResource(R.string.settings_section_devices)) }
    item { Hint(text = stringResource(R.string.settings_devices_hint)) }
    if (devices.known.isEmpty()) {
        item { Hint(text = stringResource(R.string.settings_devices_empty)) }
        return
    }
    items(devices.known, key = { device -> device.id }) { device ->
        DeviceRow(
            device = device,
            onPolicyChanged = { policy -> devices.onPolicyChanged(device.id, policy) },
            onForget = { devices.onForget(device.id) },
        )
        HorizontalDivider()
    }
    // ROUTE-002: "Auto-play is best-effort and the UI states that Android/OEM background rules may
    // prevent a cold-start connection trigger." Said once, under the list, rather than on every row.
    item { Hint(text = stringResource(R.string.settings_devices_background_hint)) }
}

/**
 * The device list and the two things that can be done to a row.
 *
 * A bundle because `SettingsScreen` is at detekt's parameter limit, and because these three always travel
 * together — a list with no way to change it would be a diagnostic, not a setting.
 */
data class DeviceSettingsActions(
    val known: List<KnownDevice> = emptyList(),
    val onPolicyChanged: (String, DevicePolicy) -> Unit = { _, _ -> },
    val onForget: (String) -> Unit = {},
)

/**
 * PRODUCT_SPEC PLAY-006 — rebuffer count and startup latency, with nowhere to put a title.
 *
 * The rebuffer count and the startup times pull in opposite directions, which is exactly why both are
 * shown: a larger buffer should reduce the first and lengthen the others, and somebody choosing a preset on
 * a slow connection needs to see the trade rather than one half of it.
 */
private fun LazyListScope.playbackMetricsRows(metrics: PlaybackMetrics) {
    if (metrics.isEmpty) {
        item { Hint(text = stringResource(R.string.about_metrics_empty)) }
        return
    }
    item {
        TextRow(labelRes = R.string.about_metrics_rebuffers, value = metrics.rebuffers.toString())
    }
    metrics.lastStartup?.let { latency ->
        item { TextRow(labelRes = R.string.about_metrics_last_startup, value = latency.asSeconds()) }
    }
    metrics.slowestStartup?.let { latency ->
        item { TextRow(labelRes = R.string.about_metrics_slowest_startup, value = latency.asSeconds()) }
    }
    item {
        TextRow(labelRes = R.string.about_metrics_startups, value = metrics.startupsMeasured.toString())
    }
    item { Hint(text = stringResource(R.string.about_metrics_hint)) }
}

/** One decimal. A startup of "1.4 s" is worth a digit; "1.437 s" is a number nobody asked for. */
@Composable
private fun Duration.asSeconds(): String = stringResource(
    R.string.about_metrics_seconds,
    String.format(Locale.getDefault(), "%.1f", inWholeMilliseconds / MILLIS_PER_SECOND),
)

private const val MILLIS_PER_SECOND = 1000.0

private enum class SettingsTab(val labelRes: Int) {
    Server(R.string.settings_tab_server),
    Playback(R.string.settings_tab_playback),
    Sleep(R.string.settings_tab_sleep),
    About(R.string.settings_tab_about),
}
