package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Immutable
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
import com.example.shelfplayer.core.designsystem.layout.centredListPadding
import com.example.shelfplayer.core.designsystem.layout.windowWidth
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.playback.DevicePolicy
import com.example.shelfplayer.core.model.playback.KnownDevice
import com.example.shelfplayer.core.model.playback.PlaybackMetrics
import com.example.shelfplayer.feature.lock.LockPreferencesUi
import com.example.shelfplayer.feature.lock.LockSettingsMessage
import com.example.shelfplayer.feature.lock.LockSettingsUiState
import com.example.shelfplayer.feature.lock.ProfileLockActions
import com.example.shelfplayer.feature.lock.ProfileLockViewModel
import com.example.shelfplayer.feature.lock.profileLockSection
import com.example.shelfplayer.launcher.LauncherIcon
import java.util.Locale
import kotlin.time.Duration

@Composable
fun SettingsRoute(
    onNavigateUp: () -> Unit,
    onManageDownloads: () -> Unit,
    onManageServerUsers: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    // PRODUCT_SPEC SET-002 — its own ViewModel, for the reason `AppearanceViewModel` records: the theme and
    // the language are read by the activity long before this screen exists, so the screen is their writer
    // and not their owner.
    appearanceViewModel: AppearanceViewModel = hiltViewModel(),
    // AUTH-005 — its own ViewModel for the same reason: `MainActivity` reads the lock to decide
    // whether to draw the app at all, so this screen is its writer rather than its owner.
    lockViewModel: ProfileLockViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val launcherIcon by viewModel.launcherIcon.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val knownDevices by viewModel.knownDevices.collectAsStateWithLifecycle()
    val metrics by viewModel.playbackMetrics.collectAsStateWithLifecycle()
    val appearance by appearanceViewModel.state.collectAsStateWithLifecycle()
    val lock by lockViewModel.state.collectAsStateWithLifecycle()
    val lockPreferences by lockViewModel.preferences.collectAsStateWithLifecycle()
    val lockMessage by lockViewModel.message.collectAsStateWithLifecycle()
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
        serverTab = ServerTabInputs(
            onDefaultLibraryChanged = viewModel::onDefaultLibraryChanged,
            account = account,
            onManageServerUsers = onManageServerUsers,
            lock = lock,
            lockPreferences = lockPreferences,
            lockActions = ProfileLockActions(
                onPasscodeSet = lockViewModel::onPasscodeSet,
                onPasscodeRemoved = lockViewModel::onPasscodeRemoved,
                onBiometricToggled = lockViewModel::onBiometricToggled,
                onRelockDelayChanged = lockViewModel::onRelockDelayChanged,
                onLockNow = lockViewModel::onLockNow,
            ),
            lockMessage = lockMessage,
        ),
        appearance = appearance,
        appearanceActions = AppearanceActions(
            onThemeModeChanged = appearanceViewModel::onThemeModeChanged,
            onDynamicColorChanged = appearanceViewModel::onDynamicColorChanged,
            onLanguageChanged = appearanceViewModel::onLanguageChanged,
        ),
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
    serverTab: ServerTabInputs,
    sleepTimerActions: SleepTimerSettingsActions,
    playbackActions: PlaybackSettingsActions,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    launcherIcon: LauncherIcon = LauncherIcon.Default,
    onLauncherIconChanged: (LauncherIcon) -> Unit = {},
    devices: DeviceSettingsActions = DeviceSettingsActions(),
    metrics: PlaybackMetrics = PlaybackMetrics.Empty,
    appearance: AppearanceUiState = AppearanceUiState(),
    appearanceActions: AppearanceActions = AppearanceActions(),
) {
    var selected by rememberSaveable { mutableStateOf(SettingsTab.Server) }
    // PRODUCT_SPEC 14.4 — the event log's open/closed state is this screen's, not the caller's. Lifting it
    // would add a parameter and a callback to a signature that is already at detekt's limit, to describe a
    // sheet nothing outside this screen can open.
    var isEventLogOpen by rememberSaveable { mutableStateOf(false) }
    // PRODUCT_SPEC 14.4 — the debug console, alongside the event log for the same reason it is on this tab:
    // both exist to be *reported*, and the console is the log plus everything around it.
    var isDebugConsoleOpen by rememberSaveable { mutableStateOf(false) }
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
                // PRODUCT_SPEC 4 / §51 — every row here is a label and a control, and on a tablet the
                // two end up a hand-span apart with nothing between them. The column keeps a readable
                // measure and the window keeps the rest; see `centredListPadding` for why this is padding
                // rather than a width cap on each row.
                contentPadding = centredListPadding(width = windowWidth(), bottom = 24.dp),
            ) {
                when (selected) {
                    SettingsTab.Server -> serverTab(uiState, serverTab)
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
                        appearance = AppearanceInputs(appearance, appearanceActions),
                        onOpenEventLog = { isEventLogOpen = true },
                        onOpenDebugConsole = { isDebugConsoleOpen = true },
                    )
                }
            }
        }
    }

    if (isEventLogOpen) EventLogSheet(onDismiss = { isEventLogOpen = false })
    if (isDebugConsoleOpen) DebugConsoleSheet(onDismiss = { isDebugConsoleOpen = false })
}

/** PRODUCT_SPEC SET-002 — which server, and which of its libraries. */
private fun LazyListScope.serverTab(uiState: SettingsUiState, inputs: ServerTabInputs) {
    item { SectionHeader(text = stringResource(R.string.settings_section_server)) }
    uiState.server?.let { server -> serverInfoRows(server) }

    // PRODUCT_SPEC 5.1 / USER-001 — who is signed in, what they may do, and the administrators-only screen.
    accountSection(inputs.account, inputs.onManageServerUsers)

    // AUTH-005 / 3.3 — the passcode lock belongs to the **Profiles** group, beside the account
    // it protects rather than under Appearance with the things that only change how the app looks.
    profileLockSection(inputs.lock, inputs.lockPreferences, inputs.lockActions, inputs.lockMessage)

    item { SectionHeader(text = stringResource(R.string.settings_section_libraries)) }
    if (uiState.libraries.isEmpty()) {
        item { Hint(text = stringResource(R.string.settings_libraries_empty)) }
    } else {
        items(uiState.libraries, key = { it.id.value }) { library ->
            LibraryRow(
                library = library,
                isDefault = library.id == uiState.defaultLibraryId,
                onToggled = { isDefault -> inputs.onDefaultLibraryChanged(library.id.takeIf { isDefault }) },
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
    appearance: AppearanceInputs,
    onOpenEventLog: () -> Unit,
    onOpenDebugConsole: () -> Unit,
) {
    item { SectionHeader(text = stringResource(R.string.about_section_app)) }
    item { TextRow(labelRes = R.string.about_version, value = uiState.versionName) }
    item { Hint(text = stringResource(R.string.about_phase)) }

    // PRODUCT_SPEC SET-002 — theme, colours and language, above the icon because they are the same kind of
    // question and this is the half somebody came here to change.
    appearanceSection(appearance.state, appearance.actions)

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

    // PRODUCT_SPEC 14.4 — the log plus everything around it, in one copyable block.
    item { Hint(text = stringResource(R.string.debug_console_body)) }
    item {
        TextButton(onClick = onOpenDebugConsole, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(text = stringResource(R.string.debug_console_open))
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

/**
 * PRODUCT_SPEC SET-002 / USER-001 — what the Server tab needs beyond [SettingsUiState].
 *
 * A bundle rather than three parameters, for the reason detekt's limit exists: `SettingsScreen` was already
 * at ten, and the eleventh is where an argument list stops being readable. The account travels with the two
 * callbacks because it decides whether one of them is reachable at all, and separating a permission from the
 * action it gates is how the two end up disagreeing.
 */
@Immutable
data class ServerTabInputs(
    val onDefaultLibraryChanged: (LibraryId?) -> Unit = {},
    /**
     * PRODUCT_SPEC 5.1 / 5.2 — the signed-in account, or `null` while none is active.
     *
     * The whole profile rather than an `isAdmin` boolean, which is what this used to be. The boolean could
     * only decide whether to draw a row; the profile can also say *why* the row is unusable, and a device
     * run proved that difference matters — an account with no administrator grant looked to its owner like
     * a feature that had never been built.
     */
    val account: Profile? = null,
    val onManageServerUsers: () -> Unit = {},
    /**
     * AUTH-005 — the passcode lock's state and controls.
     *
     * Carried in this bundle rather than added to `SettingsScreen`'s signature, which is already at
     * detekt's readable limit. They belong here on merit too: the lock is a property of the *account* the
     * Server tab is about.
     */
    val lock: LockSettingsUiState = LockSettingsUiState(),
    val lockPreferences: LockPreferencesUi = LockPreferencesUi(),
    val lockActions: ProfileLockActions = ProfileLockActions(),
    /** PRODUCT_SPEC 21 — what the last passcode write did, so a refusal is visible. */
    val lockMessage: LockSettingsMessage? = null,
)

/**
 * PRODUCT_SPEC SET-002 — the appearance state and its three writes, travelling together.
 *
 * A bundle so that `aboutTab` keeps a readable parameter list: it is at detekt's function limit with the
 * two it already has, and a state and its actions are never useful apart.
 */
@Immutable
data class AppearanceInputs(
    val state: AppearanceUiState = AppearanceUiState(),
    val actions: AppearanceActions = AppearanceActions(),
)
