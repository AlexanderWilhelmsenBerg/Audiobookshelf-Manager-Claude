package com.example.shelfplayer.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.common.connectivity.NetworkMonitor
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerStatus
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.library.BookSortOrder
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.ObserveAccessibleBooksUseCase
import com.example.shelfplayer.domain.usecase.ObserveRealtimeUpdatesUseCase
import com.example.shelfplayer.domain.usecase.ObserveSyncStateUseCase
import com.example.shelfplayer.domain.usecase.RefreshLibraryUseCase
import com.example.shelfplayer.domain.usecase.SyncAccountUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PRODUCT_SPEC LIB-001 / LIB-002 — the shelf the app opens on.
 *
 * It lists **books**, not libraries: every book the active profile is granted, most recently played
 * first. With one library, a list of libraries is a single card between the user and their shelf; with
 * several it is a menu rather than a shelf. Browsing *by* library is still possible and lives in Settings
 * (PRODUCT_SPEC SET-002) — as a list of libraries to open, not as a switch that changes what this screen
 * is.
 *
 * The ViewModel never touches the gateway (PRODUCT_SPEC 22.8): it observes Room through use cases
 * and asks a use case to refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    observeAccessibleBooks: ObserveAccessibleBooksUseCase,
    observeSyncState: ObserveSyncStateUseCase,
    profileRepository: ProfileRepository,
    private val networkMonitor: NetworkMonitor,
    private val syncAccount: SyncAccountUseCase,
    private val observeRealtimeUpdates: ObserveRealtimeUpdatesUseCase,
    private val refreshLibrary: RefreshLibraryUseCase,
) : ViewModel() {

    private val refreshState = MutableStateFlow(RefreshState())
    private val controls = MutableStateFlow(ShelfControls())

    /**
     * PRODUCT_SPEC LIB-002 — the 300 ms debounce applies to typing only.
     *
     * Changing the sort order re-queries immediately: that is one discrete choice, not a stream of
     * keystrokes, and there is nothing to settle.
     */
    private val books: Flow<List<Book>> = combine(
        controls.map { it.query }.distinctUntilChanged()
            .debounce { query -> if (query.isEmpty()) 0L else SEARCH_DEBOUNCE_MILLIS },
        controls.map { it.order }.distinctUntilChanged(),
    ) { query, order -> ShelfQuery(query, order) }
        .flatMapLatest { request -> observeAccessibleBooks(query = request.query, order = request.order) }

    /**
     * PRODUCT_SPEC LIB-002 — the refresh's own state and the device's, read together.
     *
     * Paired rather than added as a sixth source because `combine` stops at five typed flows, and
     * because the two are read together anyway: a failed refresh means something different depending on
     * whether there was a network to fail on.
     */
    private val attempt: Flow<Attempt> =
        combine(refreshState, networkMonitor.isOnline) { refresh, online -> Attempt(refresh, online) }

    val uiState: StateFlow<HomeUiState> = combine(
        profileRepository.observeActiveProfile(),
        books,
        observeSyncState(),
        attempt,
        controls,
    ) { profile, shelf, syncState, (refresh, isOnline), current ->
        HomeUiState(
            isOffline = !isOnline,
            profile = profile,
            books = shelf,
            query = current.query,
            order = current.order,
            isRefreshing = refresh.inFlight,
            syncStatus = when {
                refresh.inFlight -> SyncStatus.Syncing
                // A failure from the user's own refresh outranks the persisted state, the same way the
                // error field does: it is the newer fact and the one they are waiting on.
                refresh.lastError != null -> SyncStatus.Failed
                // The persisted state comes next, because it is the only record of a sync this process
                // did not start — one abandoned by an earlier launch, or one another screen began.
                //
                // `NeverSynced` is the exception, and it has to be: the repository reports it both for
                // "no sync has run" and for "there is no row", and content on screen proves a sync ran.
                // Labelling a populated library "not synchronized yet" would be the app contradicting
                // what the user is looking at.
                syncState != null && syncState.status != SyncStatus.NeverSynced -> syncState.status
                shelf.isNotEmpty() -> SyncStatus.Succeeded
                else -> SyncStatus.NeverSynced
            },
            // A live error from the user's own refresh wins: it is the newer fact, and it is the one they
            // are waiting for an answer to.
            error = refresh.lastError ?: syncState?.lastError,
            serverStatus = serverStatusOf(isOnline, refresh, syncState),
            // PRODUCT_SPEC LIB-001 — "the home screen can render partial cached content while sync
            // continues", and sync status is "visible but non-blocking".
            //
            // This is the only thing that blocks, and it blocks on Room rather than on the network: the
            // instant between the screen appearing and the first database emission. Blocking on the
            // *sync* was the previous behaviour and was reported from a device as having to wait for the
            // library to update before seeing it. A sync of a real library is an N+1 over every item; the
            // cached content is right there, and there is no reason to hide it meanwhile.
            isLoaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        // PRODUCT_SPEC 16.3: no unbounded collection in a lifecycle owner. The upstream is
        // dropped five seconds after the last collector goes away, which survives a rotation
        // without keeping a DataStore read alive for a backgrounded process.
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = HomeUiState(),
    )

    /**
     * PRODUCT_SPEC LIB-001 — "initial synchronization", and picking up an abandoned one.
     *
     * This screen owns the first sync. `SignInUseCase` used to await it, in the sign-in screen's scope,
     * which a successful sign-in then cancels by popping the screen — leaving a half-finished sync and a
     * `sync_state` row stuck on [SyncStatus.Syncing]. That is the "empty library until I pressed refresh"
     * report from three device runs.
     *
     * ### The rule: one automatic sync per profile per process
     *
     * There used to be a second condition — only sync when the recorded status was [SyncStatus.NeverSynced]
     * or [SyncStatus.Syncing] — and it is gone. It meant a profile that had ever synced successfully never
     * synced again on its own, so switching to an account that was up to date last week showed last week's
     * library until the user found the refresh button. Two device runs reported it: "no sync initiated,
     * same server, different account", and progress played elsewhere not appearing until a manual refresh.
     *
     * It matters more since item visibility became per profile (PRODUCT_SPEC 5.2): what an account may see
     * is now something only its *own* sync establishes, so an account that never syncs is an account whose
     * shelf is not merely stale but empty.
     *
     * The cost is real and bounded: a sync is an N+1 over the library, and this runs it once per profile
     * per process launch rather than once per screen visit. A staleness window that skips the run when the
     * last one was minutes ago belongs with the rest of the foreground refresh policy, not here.
     *
     * Per profile, not per ViewModel: it was a single flag, so switching accounts synced the first one and
     * silently skipped every later one. A device run reported exactly that — "sync only starts on the
     * first user's login".
     */
    private val syncAttemptedFor = mutableSetOf<ProfileId>()

    /**
     * PRODUCT_SPEC LIB-002 / 6.3 — the network came back, so try again.
     *
     * A device run asked for this in as many words: "when getting connectability when the server goes
     * online after being off". Without it a user who refreshed in a lift is left holding the error until
     * they think to pull down again.
     *
     * Only a transition *into* online triggers anything, and only when the last attempt actually failed.
     * Refreshing on every connectivity change would re-sync the whole library each time a phone hops
     * between Wi-Fi and mobile, which on a 490-book library is 491 requests for nothing. A profile that
     * is up to date has no reason to care that the radio changed.
     */
    init {
        viewModelScope.launch {
            networkMonitor.isOnline
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    val state = uiState.value
                    val failed = state.error != null || state.syncStatus == SyncStatus.Failed
                    if (failed && !state.isRefreshing && state.profile != null) refresh()
                }
        }
    }

    fun onVisible() {
        val state = uiState.value
        val profileId = state.profile?.id ?: return

        // PRODUCT_SPEC SYNC-002 — and the connection, for as long as this screen is alive.
        //
        // Scoped to the ViewModel rather than to the process: a socket held open by a backgrounded app
        // is a wake lock with extra steps, and PRODUCT_SPEC SYNC-003 puts persistent background work
        // under WorkManager rather than under an open connection. `collectRealtime` guards against a
        // second collector for the same profile, because `onVisible` runs on every appearance.
        collectRealtime(profileId)

        // PRODUCT_SPEC LIB-001 / AUTH-004 — the cheap half, every time the screen appears.
        //
        // One request that brings back positions played elsewhere, a grant changed on the server, and
        // whether the account is still enabled. It is not bounded by `syncAttemptedFor` because it is
        // not the expensive one: the library sweep below is 491 requests, this is one, and the whole
        // reason it exists is so that coming back to the app does not have to cost the former.
        viewModelScope.launch { syncAccount(profileId) }

        if (profileId in syncAttemptedFor || state.isRefreshing) return
        syncAttemptedFor += profileId
        refresh()
    }

    /**
     * PRODUCT_SPEC SYNC-002 — one collector per profile, replaced when the profile changes.
     *
     * Without the guard, returning to the shelf would open a second socket beside the first: both
     * authenticated, both delivering the same events, both writing the same rows. Idempotent, and still
     * two connections to a server that only needed one.
     */
    private var realtimeJob: kotlinx.coroutines.Job? = null
    private var realtimeProfile: ProfileId? = null

    private fun collectRealtime(profileId: ProfileId) {
        if (realtimeProfile == profileId && realtimeJob?.isActive == true) return
        realtimeJob?.cancel()
        realtimeProfile = profileId
        realtimeJob = viewModelScope.launch { observeRealtimeUpdates(profileId) }
    }

    fun refresh() {
        if (refreshState.value.inFlight) return
        refreshState.update { it.copy(inFlight = true) }
        viewModelScope.launch {
            val result = refreshLibrary()
            refreshState.update {
                it.copy(
                    inFlight = false,
                    lastError = (result as? AppResult.Failure)?.error,
                    lastAttemptSucceeded = result is AppResult.Success,
                )
            }
        }
    }

    fun onQueryChanged(query: String) {
        controls.update { it.copy(query = query) }
    }

    fun onOrderChanged(order: BookSortOrder) {
        controls.update { it.copy(order = order) }
    }

    fun dismissError() {
        refreshState.update { it.copy(lastError = null) }
    }

    private data class RefreshState(
        val inFlight: Boolean = false,
        val lastError: AppError? = null,
        /** Distinct from `lastError == null`, which is also true before anything has been attempted. */
        val lastAttemptSucceeded: Boolean = false,
    )

    /** What the user has asked the shelf to show: the default order is what was played last. */
    private data class ShelfControls(val query: String = "", val order: BookSortOrder = BookSortOrder.LastPlayed)

    /** One resolved request: what to search for and how to order the result. */
    private data class ShelfQuery(val query: String, val order: BookSortOrder)

    /** A refresh's own state alongside the device's, so the UI can tell "no network" from "server said no". */
    private data class Attempt(val refresh: RefreshState, val isOnline: Boolean)

    /**
     * PRODUCT_SPEC LIB-002 / SYNC-001 — the reachability indicator, inferred from calls already made.
     *
     * There is deliberately no separate ping. A dedicated probe would answer a slightly different
     * question than the one the user cares about — "does the health endpoint respond" rather than "is my
     * library working" — and would cost a request per interval to do it. The outcome of the sync the app
     * just performed is both free and more truthful.
     *
     * Offline outranks everything: with no network there is nothing to say about the server, and showing
     * it as unreachable would blame the wrong thing. That is why this is three states rather than a
     * boolean — "unknown" is a real answer and the honest one before anything has been attempted.
     */
    private fun serverStatusOf(isOnline: Boolean, refresh: RefreshState, syncState: SyncState?): ServerStatus = when {
        !isOnline -> ServerStatus.Unknown
        refresh.lastError != null -> if (refresh.lastError.isReachability()) {
            ServerStatus.Unreachable
        } else {
            // A `401` or a `403` is a server that answered — clearly reachable, and saying otherwise
            // would send the user to check their network over a permissions problem.
            ServerStatus.Reachable
        }

        refresh.lastAttemptSucceeded -> ServerStatus.Reachable
        syncState?.status == SyncStatus.Failed ->
            if (syncState.lastError?.isReachability() == true) ServerStatus.Unreachable else ServerStatus.Reachable

        syncState?.lastSuccessfulSyncAt != null -> ServerStatus.Reachable
        else -> ServerStatus.Unknown
    }

    private fun AppError.isReachability(): Boolean = this is AppError.Network || this is AppError.Timeout

    private companion object {
        /** PRODUCT_SPEC LIB-002: search debounce is 300 ms. */
        const val SEARCH_DEBOUNCE_MILLIS = 300L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** PRODUCT_SPEC 21 — loading, empty, error and content are separate, observable states. */
data class HomeUiState(
    val profile: Profile? = null,
    val books: List<Book> = emptyList(),
    val query: String = "",
    val order: BookSortOrder = BookSortOrder.LastPlayed,
    val isRefreshing: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.NeverSynced,
    val error: AppError? = null,
    /**
     * PRODUCT_SPEC LIB-002 / SYNC-001 — whether the *server* is answering, as opposed to whether the
     * device has a network.
     *
     * Asked for from a device: "an icon on top to show that the server is reachable or not". The two
     * are genuinely different questions and both are shown — a LAN-only server is unreachable over a
     * perfectly good mobile connection, and reporting that as "offline" would send the user to check
     * their phone instead of their VPN.
     */
    val serverStatus: ServerStatus = ServerStatus.Unknown,
    /**
     * PRODUCT_SPEC LIB-002 — "empty, loading, error, and offline states are distinct".
     *
     * Distinct because they call for different sentences. An offline shelf is complete and correct as
     * far as it goes; a failed one may be missing something. Telling a user on a train that the server
     * refused their request sends them to check a server that is fine.
     *
     * It says nothing about the *server* being reachable — a LAN-only server is unreachable over a
     * mobile connection that reports online. See `NetworkMonitor`.
     */
    val isOffline: Boolean = false,
    /**
     * Whether Room has answered yet.
     *
     * `false` only for the instant between this screen appearing and its first database emission, and the
     * screen waits on it — otherwise a cold start with a saved profile flashes "No server connected"
     * before the profile arrives. It is deliberately *not* about the sync: a refresh in flight is
     * [isRefreshing], which the screen shows without hiding anything (PRODUCT_SPEC LIB-001).
     */
    val isLoaded: Boolean = false,
)
