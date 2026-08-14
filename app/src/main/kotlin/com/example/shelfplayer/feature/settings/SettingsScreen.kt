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

@Composable
fun SettingsRoute(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        uiState = uiState,
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

                    SettingsTab.Playback -> playbackTab(
                        settings = uiState.playback,
                        libraries = uiState.libraries,
                        actions = playbackActions,
                        networkPolicy = uiState.networkPolicy,
                    )

                    SettingsTab.About -> aboutTab(uiState, onOpenEventLog = { isEventLogOpen = true })
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
private fun LazyListScope.aboutTab(uiState: SettingsUiState, onOpenEventLog: () -> Unit) {
    item { SectionHeader(text = stringResource(R.string.about_section_app)) }
    item { TextRow(labelRes = R.string.about_version, value = uiState.versionName) }
    item { Hint(text = stringResource(R.string.about_phase)) }

    // PRODUCT_SPEC 14.4 — before the readings, because this is the one thing on the tab somebody reaches
    // for while something is wrong rather than while checking a build.
    item { SectionHeader(text = stringResource(R.string.about_section_diagnostics)) }
    item { Hint(text = stringResource(R.string.event_log_body)) }
    item {
        TextButton(onClick = onOpenEventLog, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(text = stringResource(R.string.event_log_open))
        }
    }

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
private enum class SettingsTab(val labelRes: Int) {
    Server(R.string.settings_tab_server),
    Playback(R.string.settings_tab_playback),
    Sleep(R.string.settings_tab_sleep),
    About(R.string.settings_tab_about),
}
