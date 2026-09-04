package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material3.TabPosition
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.BuildConfig
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
import com.example.shelfplayer.ui.gesture.offscreenPage
import com.example.shelfplayer.ui.gesture.rememberEdgeOverspill
import com.example.shelfplayer.ui.glass.playerChromeClearance
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
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
    val debugMessage by viewModel.debugMessage.collectAsStateWithLifecycle()
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
        recovery = RecoveryTestInputs(
            onExpireAccessToken = viewModel::onExpireAccessToken,
            message = debugMessage,
            onMessageShown = viewModel::onDebugMessageShown,
        ),
        appearanceActions = AppearanceActions(
            onThemeChanged = appearanceViewModel::onThemeChanged,
            onAccentChanged = appearanceViewModel::onAccentChanged,
            onGlassTintChanged = appearanceViewModel::onGlassTintChanged,
            onCardGlassTintChanged = appearanceViewModel::onCardGlassTintChanged,
            onSystemGlassTintChanged = appearanceViewModel::onSystemGlassTintChanged,
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
            onAutoAdvanceSeriesChanged = viewModel::onAutoAdvanceSeriesChanged,
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
    // AUTH-004 — debug only, and defaulted so `SettingsScreen` stays a pure function its tests can
    // render without a ViewModel (PRODUCT_SPEC 16.4).
    recovery: RecoveryTestInputs = RecoveryTestInputs(),
) {
    // The first tab, whichever that is. Named through `entries` rather than by hand so that reordering
    // the enum cannot leave the screen opening on a tab that is no longer the one the pager starts on —
    // which is exactly what happened when Appearance was put in front of Server.
    var selected by rememberSaveable { mutableStateOf(SettingsTab.entries.first()) }
    /*
     * PRODUCT_SPEC SET-002 / 16.2 — the tabs are pages, and the drag is the transition.
     *
     * [selected] stays the saved truth — it is what survives a rotation, and what a tap sets — and the
     * pager is kept in step with it in both directions. `settledPage` rather than `currentPage` on the
     * way back, so a drag the user abandons half-way does not change the tab it sprang back from.
     */
    val pagerState = rememberPagerState(
        initialPage = selected.ordinal,
        pageCount = { SettingsTab.entries.size },
    )
    LaunchedEffect(selected) {
        if (selected.ordinal != pagerState.currentPage) pagerState.animateScrollToPage(selected.ordinal)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            SettingsTab.entries.getOrNull(page)?.let { settled -> selected = settled }
        }
    }
    /** Where the underline is, as a fractional tab index. See [TabIndicator]. */
    val tabPosition = pagerState.currentPage + pagerState.currentPageOffsetFraction
    // Read here rather than in the tab: a `LazyListScope` builder is not a composable, and the swatches
    // need to know which ground they are previewing themselves against.
    val systemIsDark = isSystemInDarkTheme()
    /*
     * PRODUCT_SPEC SET-002 / 16.2 — the give at both ends, and the pull that leaves the screen.
     *
     * One component for both because they are one gesture: at the first tab there is no page to the left,
     * so a drag back is overspill, and letting go past the threshold is what leaves for the shelf. Two
     * nested-scroll connections reading the same delta would each count it and the screen would leave at
     * half the distance it looks like.
     */
    val overspill = rememberEdgeOverspill(onPulledPastStart = onNavigateUp)
    // PRODUCT_SPEC 14.4 — the event log's open/closed state is this screen's, not the caller's. Lifting it
    // would add a parameter and a callback to a signature that is already at detekt's limit, to describe a
    // sheet nothing outside this screen can open.
    var isEventLogOpen by rememberSaveable { mutableStateOf(false) }
    // PRODUCT_SPEC 14.4 — the debug console, alongside the event log for the same reason it is on this tab:
    // both exist to be *reported*, and the console is the log plus everything around it.
    var isDebugConsoleOpen by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        /*
         * PRODUCT_SPEC SET-002 — transparent so the app's backdrop shows through.
         *
         * `Scaffold` paints its container over everything beneath it, so the gradient drawn behind the
         * navigation graph would be covered here and the glass cards would be frosting an opaque surface
         * — a blur of one flat colour, which is that same flat colour. The same trap `TopAppBar` sets
         * with its own container, and the reason a frosted surface has to be told to stop painting.
         */
        containerColor = Color.Transparent,
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
                .padding(innerPadding)
                /*
                 * PRODUCT_SPEC SET-002 / 16.2 — the give at the ends, and the pull that leaves.
                 *
                 * Dragging back past the first tab leaves the screen entirely: Server is the leftmost tab,
                 * there is nothing to its left inside this screen, and what is behind the screen is the
                 * shelf. A pager has no page before its first, so the drag comes out of it unconsumed and
                 * `rememberEdgeOverspill` is what catches it — both to move the page and to notice the
                 * pull. On the parent rather than the pager so it sees the drag, not the drag's result.
                 */
                .nestedScroll(overspill.connection),
        ) {
            /*
             * PRODUCT_SPEC 16.2 — the indicator follows the drag rather than jumping when it lands.
             *
             * `TabRow` positions its own indicator from `selectedTabIndex`, which is an `Int` — so during
             * a drag it either sits still or snaps. Its `indicator` slot takes the tab positions, and
             * interpolating between the two the drag is between is what makes the underline travel with
             * the page. `selectedTabIndex` still drives the *labels*, which have nothing to interpolate.
             */
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                indicator = { positions -> TabIndicator(positions = positions, position = tabPosition) },
            ) {
                SettingsTab.entries.forEach { tab ->
                    Tab(
                        selected = tab == selected,
                        onClick = { selected = tab },
                        text = { Text(text = stringResource(tab.labelRes)) },
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(x = overspill.offsetPx.roundToInt(), y = 0) },
                // The platform's stretch would consume the delta at the edges before `overspill` could see
                // it — and the pull past the first tab is how this screen is left.
                overscrollEffect = null,
                // Both neighbours composed, so a drag reveals the real tab from its first pixel. Settings
                // tabs are cheap — every one of them renders from state already in hand — which is why
                // this is affordable here and deliberately narrower on Home.
                beyondViewportPageCount = 1,
                key = { page -> SettingsTab.entries[page].name },
            ) { page ->
                val tab = SettingsTab.entries[page]
                LazyColumn(
                    // A page that is not on screen stays out of the accessibility tree — see
                    // `offscreenPage`, and the two tests that found this.
                    modifier = Modifier
                        .fillMaxSize()
                        .offscreenPage(isCurrent = page == pagerState.currentPage),
                    // PRODUCT_SPEC 4 / §51 — every row here is a label and a control, and on a tablet the
                    // two end up a hand-span apart with nothing between them. The column keeps a readable
                    // measure and the window keeps the rest; see `centredListPadding` for why this is
                    // padding rather than a width cap on each row.
                    contentPadding = centredListPadding(
                        width = windowWidth(),
                        // Plus whatever the floating mini player is covering, so the last row can be read.
                        bottom = 24.dp + playerChromeClearance(),
                    ),
                ) {
                    when (tab) {
                        SettingsTab.Appearance -> appearanceTab(
                            // The theme's own answer where it has one, and the device's where it does
                            // not. The swatches preview themselves against the ground the reader is
                            // actually on, and `AppTheme.System` cannot say which that is.
                            state = appearance.copy(isDark = appearance.theme.isDark ?: systemIsDark),
                            actions = appearanceActions,
                        )

                        SettingsTab.Playback -> {
                            playbackTab(
                                settings = uiState.playback,
                                libraries = uiState.libraries,
                                actions = playbackActions,
                                networkPolicy = uiState.networkPolicy,
                                housekeeping = uiState.housekeeping,
                            )
                            // PRODUCT_SPEC PLAY-008 — the sleep timer's own tab is gone; this is it.
                            sleepTimerTab(
                                settings = uiState.sleepTimer,
                                history = uiState.sleepTimerHistory,
                                actions = sleepTimerActions,
                            )
                            devicesSection(devices)
                        }

                        SettingsTab.Server -> serverTab(uiState, serverTab)

                        SettingsTab.About -> aboutTab(
                            uiState = uiState,
                            launcherIcon = launcherIcon,
                            onLauncherIconChanged = onLauncherIconChanged,
                            metrics = metrics,
                            diagnostics = DiagnosticsInputs(
                                onOpenEventLog = { isEventLogOpen = true },
                                onOpenDebugConsole = { isDebugConsoleOpen = true },
                            ),
                            recovery = recovery,
                        )
                    }
                }
            }
        }
    }

    if (isEventLogOpen) EventLogSheet(onDismiss = { isEventLogOpen = false })
    if (isDebugConsoleOpen) DebugConsoleSheet(onDismiss = { isDebugConsoleOpen = false })
}

/**
 * Which two tabs a fractional position lies between, and how far along.
 *
 * Its own function because it is the only part of the indicator with a rule in it, and the rule has two
 * edges that are easy to get wrong: a position at or past the **last** tab must not index one beyond the
 * end, and a position outside the range at either side must clamp rather than wrap. Both are reachable —
 * `currentPageOffsetFraction` is signed, so the position dips below zero on an over-drag at the first tab.
 */
internal fun tabBlend(count: Int, position: Float): TabBlend {
    val clamped = position.coerceIn(0f, (count - 1).coerceAtLeast(0).toFloat())
    val lower = floor(clamped).toInt()
    return TabBlend(from = lower, to = ceil(clamped).toInt(), fraction = clamped - lower)
}

/** The two tabs an indicator is between, and the fraction of the way from [from] to [to]. */
internal data class TabBlend(val from: Int, val to: Int, val fraction: Float)

/**
 * PRODUCT_SPEC SET-002 / 16.2 — the tab underline, placed at a **fractional** tab index.
 *
 * `TabRow`'s own indicator is positioned from an `Int`, so during a drag it either sits still until the
 * page lands or snaps to it. This one is drawn between the two tabs the drag is between: at 1.4 it is
 * 40% of the way from the second to the third, in both position and width, so a drag that springs back
 * carries the underline back with it.
 *
 * `lerp` on the width as well as the offset because the tabs are not all the same width — *Playback* is
 * wider than *Sleep* — and an indicator that kept one width while travelling would arrive too short or
 * too long.
 */
@Composable
private fun TabIndicator(positions: List<TabPosition>, position: Float) {
    if (positions.isEmpty()) return
    val blend = tabBlend(count = positions.size, position = position)
    val from = positions[blend.from]
    val to = positions[blend.to]
    val fraction = blend.fraction
    TabRowDefaults.SecondaryIndicator(
        modifier = Modifier
            .wrapContentSize(Alignment.BottomStart)
            .offset(x = lerp(from.left, to.left, fraction))
            .width(lerp(from.width, to.width, fraction)),
    )
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
    diagnostics: DiagnosticsInputs,
    recovery: RecoveryTestInputs,
) {
    item { SectionHeader(text = stringResource(R.string.about_section_app)) }
    item { TextRow(labelRes = R.string.about_version, value = uiState.versionLabel) }
    // Which build this is, for a report filed against it. The commit pins it to one revision; the type
    // separates a debug APK from a release one on a device where both can be installed side by side.
    item { TextRow(labelRes = R.string.about_build, value = uiState.buildLabel) }
    // And which branch it came from. This row exists because the version no longer says: the code is a
    // build counter now, so a tester holding two APKs reads the branch here rather than decoding a number.
    item { TextRow(labelRes = R.string.about_source, value = uiState.sourceLabel) }
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
        TextButton(onClick = diagnostics.onOpenEventLog, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(text = stringResource(R.string.event_log_open))
        }
    }

    // PRODUCT_SPEC 14.4 — the log plus everything around it, in one copyable block.
    item { Hint(text = stringResource(R.string.debug_console_body)) }
    item {
        TextButton(onClick = diagnostics.onOpenDebugConsole, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(text = stringResource(R.string.debug_console_open))
        }
    }

    // AUTH-004 — the control that makes the renewal path reachable on a device. Debug builds only, and
    // the gate is here rather than in the ViewModel so the row simply does not exist in a release APK.
    if (BuildConfig.DEBUG) recoveryTestRows(recovery)

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
 * AUTH-004 — one button that puts this install into the state the renewal path exists for.
 *
 * `docs/testing/pr-playback-auth-recovery.md` has eleven steps and each begins by asking the tester to
 * reproduce an expired access token beside a valid refresh token. Doing that from outside the app means an
 * intercepting proxy or a shortened server-side token lifetime, and neither is available to somebody
 * holding a phone in a car — which is where the defect was found. Pressing this while a book plays makes
 * the next media range request receive a real `401` from a real server.
 *
 * Debug builds only. The caller gates it, so a release APK has no row here at all.
 */
private fun LazyListScope.recoveryTestRows(inputs: RecoveryTestInputs) {
    item { SubHeader(text = stringResource(R.string.about_recovery_test)) }
    item { Hint(text = stringResource(R.string.about_recovery_test_body)) }
    item {
        TextButton(onClick = inputs.onExpireAccessToken, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(text = stringResource(R.string.about_recovery_test_expire))
        }
    }
    inputs.message?.let { message ->
        item {
            // Announced, and read once: what happened is the whole output of the button, and a sentence
            // that outlived the tap would describe a state the tester has since left.
            Hint(text = message)
            DisposableEffect(message) { onDispose(inputs.onMessageShown) }
        }
    }
}

/**
 * The two diagnostics overlays the About tab can open, bundled for the reason the other `*Inputs` types
 * are: `aboutTab` is at detekt's parameter limit and these always travel together.
 *
 * Built at the call site rather than passed in from [SettingsRoute], because both open a sheet whose
 * open/closed state belongs to [SettingsScreen] and never leaves it.
 */
@Immutable
data class DiagnosticsInputs(val onOpenEventLog: () -> Unit = {}, val onOpenDebugConsole: () -> Unit = {})

/**
 * The debug recovery control's three parameters, bundled for the reason the other `*Inputs` types are:
 * `aboutTab` is at detekt's limit and these always travel together.
 */
@Immutable
data class RecoveryTestInputs(
    val onExpireAccessToken: () -> Unit = {},
    val message: String? = null,
    val onMessageShown: () -> Unit = {},
)

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

/**
 * PRODUCT_SPEC SET-002 — the tabs, in the order the owner asked for.
 *
 * Appearance first because it is what somebody opens Settings to change; About last because it is the one
 * nobody opens twice. **Sleep is gone** — its controls are the second half of the Playback tab now. It was
 * a whole tab for one feature, and the feature it belongs to is how playback behaves: a reader setting a
 * default speed and a reader setting a sleep timer are the same reader on the same errand.
 */
private enum class SettingsTab(val labelRes: Int) {
    Appearance(R.string.settings_tab_appearance),
    Playback(R.string.settings_tab_playback),
    Server(R.string.settings_tab_server),
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
