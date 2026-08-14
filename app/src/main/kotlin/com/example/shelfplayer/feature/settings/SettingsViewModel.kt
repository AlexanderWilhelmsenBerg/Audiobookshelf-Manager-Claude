package com.example.shelfplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.BuildConfig
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.StorageDiagnostics
import com.example.shelfplayer.core.model.download.NetworkPolicy
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.playback.AutoRewind
import com.example.shelfplayer.core.model.playback.BufferPreset
import com.example.shelfplayer.core.model.playback.CarReadiness
import com.example.shelfplayer.core.model.playback.NotificationAccess
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.model.playback.PlaybackSpeed
import com.example.shelfplayer.core.model.playback.SessionSyncDiagnostics
import com.example.shelfplayer.core.model.playback.SkipIntervals
import com.example.shelfplayer.core.model.playback.SleepTimerSession
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.domain.repository.DiagnosticsRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.example.shelfplayer.domain.repository.PreferencesRepository
import com.example.shelfplayer.domain.repository.SessionSyncRepository
import com.example.shelfplayer.domain.repository.SleepTimerRepository
import com.example.shelfplayer.domain.usecase.ObserveLibrariesUseCase
import com.example.shelfplayer.domain.usecase.ObserveServerDiagnosticsUseCase
import com.example.shelfplayer.domain.usecase.ServerDiagnostics
import com.example.shelfplayer.playback.CarReadinessReader
import com.example.shelfplayer.playback.NotificationAccessReader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PRODUCT_SPEC SET-001 / SET-002 / SYNC-001 — everything the settings screen shows, across both tabs.
 *
 * ### Why one ViewModel for two tabs
 *
 * The tabs are a layout, not a boundary. They are two views of one screen's state, they switch without
 * navigating, and both are alive the moment the screen is. A ViewModel each would mean two independent
 * subscriptions to the same profile, two `WhileSubscribed` timers, and a tab switch that could show one
 * tab's idea of the active profile beside the other's. The earlier split — a separate About destination
 * with its own ViewModel — was a *navigation* boundary, and folding it in is what removed the need for
 * it.
 *
 * ### What goes where
 *
 * - **Server**: the address, what the server reported about itself, and the libraries this profile may
 *   open — including which one the shelf opens on, the single real preference here.
 * - **About**: the app's version, and the readings under a *Testing* heading. Those exist to make an
 *   acceptance case checkable on a device rather than through `adb`, which is why they are labelled as
 *   such and kept away from the preference.
 *
 * The capability list is the one server fact under Testing rather than under Server. It is not
 * information about the server so much as a record of what this build asked it, and a device run was
 * explicit that it belonged with the test readings.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    observeLibraries: ObserveLibrariesUseCase,
    observeServerDiagnostics: ObserveServerDiagnosticsUseCase,
    diagnostics: DiagnosticsRepository,
    private val preferences: PreferencesRepository,
    private val sleepTimer: SleepTimerRepository,
    sessionSync: SessionSyncRepository,
    /**
     * PRODUCT_SPEC PLAY-001 — read directly from `:playback` rather than through a domain repository.
     *
     * It is a question about the *device's* notification settings and about Media3's own channel, not about
     * this account's data, so there is nothing for a repository to mediate. `:app` already reaches into
     * `:playback` for the controller and the sleep timer, and inventing a repository interface for a two-line
     * platform read would be the parallel abstraction CLAUDE.md warns about.
     */
    private val notifications: NotificationAccessReader,
    /**
     * PRODUCT_SPEC PLAY-001 / ROUTE-002 — why the app is, or is not, in a car's app list.
     *
     * Here for the same reason [notifications] is: it is a question about the *device* — what Android Auto is
     * installed, what installed this build — rather than about this account's data, so there is nothing for a
     * repository to mediate.
     */
    private val car: CarReadinessReader,
    private val playbackSettings: PlaybackSettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        observeLibraries(),
        preferences.observePreferences(),
        observeServerDiagnostics(),
        diagnostics.observeStorage(),
        // PRODUCT_SPEC PLAY-008 / SET-002 — the timer's defaults and the history they produced, which
        // are read together: the screen that turns shake-to-restart on is the screen that shows how
        // often it fired.
        //
        // The session outbox joins them rather than becoming a sixth source: `combine`'s typed overloads
        // stop at five, and the untyped one loses every parameter's type in the lambda.
        combine(
            sleepTimer.observeSettings(),
            sleepTimer.observeRecentSessions(),
            sessionSync.observeDiagnostics(),
            playbackSettings.observeSettings(),
            // PRODUCT_SPEC DL-004 — the network policy rides with the playback settings because it comes
            // from the same store and is rendered on the same tab.
            playbackSettings.observeNetworkPolicy(),
            ::Playback,
        ),
    ) { libraries, stored, server, storage, playback ->
        SettingsUiState(
            libraries = libraries,
            // PRODUCT_SPEC 6.1 step 9 — resolved against the granted libraries rather than shown raw.
            // A default library the profile has since lost is not a default any more, and rendering the
            // stored id would put a tick beside a library that is no longer in the list.
            defaultLibraryId = stored.defaultLibraryId?.takeIf { id -> libraries.any { it.id == id } },
            server = server,
            storage = storage,
            sleepTimer = playback.timer,
            sleepTimerHistory = playback.timerHistory,
            sessionSync = playback.sessionSync,
            playback = playback.controls,
            networkPolicy = playback.network,
            // Read per emission rather than observed: notification state has no change callback, and this
            // flow already re-runs whenever anything it depends on moves — which on the About tab is often
            // enough to be current while somebody is looking at it.
            notifications = notifications.read(),
            // Read per emission for the reason above it: neither the installed set of apps nor "has a car
            // connected yet" has a change callback, and this flow re-runs often enough to be current.
            car = car.read(),
            versionName = BuildConfig.VERSION_NAME,
            isLoaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        // PRODUCT_SPEC 16.3: no unbounded collection in a lifecycle owner.
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SettingsUiState(versionName = BuildConfig.VERSION_NAME),
    )

    /**
     * PRODUCT_SPEC 6.1 step 9 — chooses the library the app opens on, or clears the choice.
     *
     * Clearing is not "choose the library that has everything": it returns the shelf to every library
     * the profile is granted, which is a different list the moment a second library is added.
     */
    fun onDefaultLibraryChanged(libraryId: LibraryId?) {
        viewModelScope.launch { preferences.setDefaultLibrary(libraryId) }
    }

    /**
     * PRODUCT_SPEC DL-004 / ADR-0018 decision 5 — which categories may spend cellular data.
     *
     * Takes effect on the *next* enqueue rather than retroactively. A download already waiting for Wi-Fi
     * keeps its constraint, because WorkManager fixes constraints at enqueue time — so a user who turns
     * cellular on to unstick a waiting download has to tap the button again. That is worth knowing and is
     * not worth cancelling and re-enqueueing every queued job to avoid.
     */
    fun onNetworkPolicyChanged(policy: NetworkPolicy) {
        viewModelScope.launch { playbackSettings.setNetworkPolicy(policy) }
    }

    /** PRODUCT_SPEC PLAY-008 — "requires explicit opt-in". This toggle is that opt-in. */
    fun onShakeToRestartChanged(enabled: Boolean) {
        viewModelScope.launch { sleepTimer.setShakeToRestart(enabled) }
    }

    fun onSleepTimerDefaultChanged(length: kotlin.time.Duration) {
        viewModelScope.launch { sleepTimer.setDefaultLength(length) }
    }

    fun onSleepTimerFadeChanged(length: kotlin.time.Duration) {
        viewModelScope.launch { sleepTimer.setFadeLength(length) }
    }

    /** PRODUCT_SPEC PLAY-008 / PLAY-009 — how far to rewind when the timer stops the book. Zero is off. */
    fun onSleepTimerRewindChanged(length: kotlin.time.Duration) {
        viewModelScope.launch { sleepTimer.setRewindOnStop(length) }
    }

    /** PRODUCT_SPEC PLAY-007 — the speed a book uses when it has no override of its own. */
    fun onDefaultSpeedChanged(speed: PlaybackSpeed) {
        viewModelScope.launch { playbackSettings.setDefaultSpeed(speed) }
    }

    fun onSkipIntervalsChanged(skips: SkipIntervals) {
        viewModelScope.launch { playbackSettings.setSkipIntervals(skips) }
    }

    /** PRODUCT_SPEC PLAY-009 — the switch and the four bands, which are one value. */
    fun onAutoRewindChanged(rewind: AutoRewind) {
        viewModelScope.launch { playbackSettings.setAutoRewind(rewind) }
    }

    fun onBufferPresetChanged(preset: BufferPreset) {
        viewModelScope.launch { playbackSettings.setBufferPreset(preset) }
    }

    /** PRODUCT_SPEC ROUTE-001 / ROUTE-002 — auto-play when a car connects. Off unless chosen. */
    fun onAutoPlayOnCarConnectChanged(enabled: Boolean) {
        viewModelScope.launch { playbackSettings.setAutoPlayOnCarConnect(enabled) }
    }

    /**
     * The three playback readings, combined before the outer `combine` sees them.
     *
     * A named type rather than a `Triple` so the outer lambda reads as what it is. It is private to the
     * ViewModel because it exists only to get past `combine`'s arity, and nothing outside should depend on the
     * three travelling together.
     */
    private data class Playback(
        val timer: SleepTimerSettings,
        val timerHistory: List<SleepTimerSession>,
        val sessionSync: SessionSyncDiagnostics,
        val controls: PlaybackSettings,
        val network: NetworkPolicy,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * @property libraries the libraries the active profile is granted — the grant is applied by the
 *   repository, so this list is what the user may open, not what exists.
 * @property server what the capability handshake learned, or `null` while no profile is active.
 * @property storage what is on disk, including rows outside that grant, as counts. The difference between
 *   the two is the point: it is how "unauthorized libraries were never written" becomes checkable.
 * @property isLoaded whether the first read has arrived. Zeroes before it has would read as facts.
 */
data class SettingsUiState(
    val libraries: List<Library> = emptyList(),
    /** PRODUCT_SPEC 6.1 step 9 — `null` is every granted library, which is the default. */
    val defaultLibraryId: LibraryId? = null,
    val server: ServerDiagnostics? = null,
    val storage: StorageDiagnostics = StorageDiagnostics(),
    /** PRODUCT_SPEC SET-002 (Playback) — the sleep timer's defaults. */
    val sleepTimer: SleepTimerSettings = SleepTimerSettings.Default,
    /** PRODUCT_SPEC PLAY-008 — what this device's timers actually did, newest first. */
    val sleepTimerHistory: List<SleepTimerSession> = emptyList(),
    /** PRODUCT_SPEC PLAY-004 / PLAY-005 — the session outbox, as counts and timings. */
    val sessionSync: SessionSyncDiagnostics = SessionSyncDiagnostics(),
    /** PRODUCT_SPEC PLAY-001 — whether the media notification can appear, and whether it has. */
    val notifications: NotificationAccess = NotificationAccess(),
    /** PRODUCT_SPEC PLAY-001 / ROUTE-002 — why the app is, or is not, in the car's list of media apps. */
    val car: CarReadiness = CarReadiness(),
    /** PRODUCT_SPEC PLAY-006 / PLAY-007 / PLAY-009 — speed, skips, auto-rewind and the buffer. */
    val playback: PlaybackSettings = PlaybackSettings.Default,
    /** PRODUCT_SPEC DL-004 — which categories may spend cellular data. */
    val networkPolicy: NetworkPolicy = NetworkPolicy.Default,
    val versionName: String = "",
    val isLoaded: Boolean = false,
)
