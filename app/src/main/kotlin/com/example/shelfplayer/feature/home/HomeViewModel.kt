package com.example.shelfplayer.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.domain.library.BookSortOrder
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.SettingsRepository
import com.example.shelfplayer.domain.usecase.ObserveAccessibleBooksUseCase
import com.example.shelfplayer.domain.usecase.ObserveLibrariesUseCase
import com.example.shelfplayer.domain.usecase.ObserveSyncStateUseCase
import com.example.shelfplayer.domain.usecase.RefreshLibraryUseCase
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
 * first. A list of libraries is still available and is a setting (PRODUCT_SPEC SET-002, Profiles),
 * because with one library it is a single card between the user and their shelf, and with several it is
 * a menu rather than a shelf.
 *
 * The ViewModel never touches the gateway (PRODUCT_SPEC 22.8): it observes Room through use cases
 * and asks a use case to refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    observeLibraries: ObserveLibrariesUseCase,
    observeAccessibleBooks: ObserveAccessibleBooksUseCase,
    observeSyncState: ObserveSyncStateUseCase,
    profileRepository: ProfileRepository,
    settings: SettingsRepository,
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
     * Grouped into one upstream so the whole state still fits the five-argument [combine].
     *
     * The alternative is the array-typed overload, which would give up the parameter names that make
     * the state builder below readable.
     */
    private val presentation: Flow<Presentation> = combine(
        refreshState,
        controls,
        settings.homeShowsLibraries,
    ) { refresh, current, showsLibraries -> Presentation(refresh, current, showsLibraries) }

    val uiState: StateFlow<HomeUiState> = combine(
        profileRepository.observeActiveProfile(),
        observeLibraries(),
        books,
        observeSyncState(),
        presentation,
    ) { profile, libraries, shelf, syncState, presentation ->
        HomeUiState(
            profile = profile,
            libraries = libraries,
            books = shelf,
            query = presentation.controls.query,
            order = presentation.controls.order,
            showsLibraries = presentation.showsLibraries,
            isRefreshing = presentation.refresh.inFlight,
            syncStatus = when {
                presentation.refresh.inFlight -> SyncStatus.Syncing
                // A failure from the user's own refresh outranks the persisted state, the same way the
                // error field does: it is the newer fact and the one they are waiting on.
                presentation.refresh.lastError != null -> SyncStatus.Failed
                // The persisted state comes next, because it is the only record of a sync the user did
                // not start — the one `SignInUseCase` runs immediately after sign-in.
                //
                // `NeverSynced` is the exception, and it has to be: the repository reports it both for
                // "no sync has run" and for "there is no row", and content on screen proves a sync ran.
                // Labelling a populated library "not synchronized yet" would be the app contradicting
                // what the user is looking at.
                syncState != null && syncState.status != SyncStatus.NeverSynced -> syncState.status
                libraries.isNotEmpty() -> SyncStatus.Succeeded
                else -> SyncStatus.NeverSynced
            },
            // A live error from the user's own refresh wins: it is the newer fact, and it is the one they
            // are waiting for an answer to.
            error = presentation.refresh.lastError ?: syncState?.lastError,
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
     * PRODUCT_SPEC LIB-001 — "initial synchronization".
     *
     * A profile that has never synced gets one attempt without the user asking. This is the self-healing
     * half of the bug reported from a device: signing in ran a sync, something went wrong with it, and the
     * app then sat on an empty library waiting to be told to try again.
     *
     * Bounded to one attempt per ViewModel instance and only from [SyncStatus.NeverSynced], so a server
     * that fails repeatedly is not retried in a loop — PRODUCT_SPEC LIB-001 wants sync status visible and
     * non-blocking, not persistent. A failure leaves the recorded state on screen with a retry button.
     */
    private var initialSyncAttempted = false

    fun onVisible() {
        val state = uiState.value
        if (initialSyncAttempted || state.profile == null) return
        if (state.syncStatus != SyncStatus.NeverSynced) return
        initialSyncAttempted = true
        refresh()
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

    private data class RefreshState(val inFlight: Boolean = false, val lastError: AppError? = null)

    /** What the user has asked the shelf to show: the default order is what was played last. */
    private data class ShelfControls(val query: String = "", val order: BookSortOrder = BookSortOrder.LastPlayed)

    /** One resolved request: what to search for and how to order the result. */
    private data class ShelfQuery(val query: String, val order: BookSortOrder)

    private data class Presentation(
        val refresh: RefreshState,
        val controls: ShelfControls,
        val showsLibraries: Boolean,
    )

    private companion object {
        /** PRODUCT_SPEC LIB-002: search debounce is 300 ms. */
        const val SEARCH_DEBOUNCE_MILLIS = 300L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** PRODUCT_SPEC 21 — loading, empty, error and content are separate, observable states. */
data class HomeUiState(
    val profile: Profile? = null,
    val libraries: List<Library> = emptyList(),
    val books: List<Book> = emptyList(),
    val query: String = "",
    val order: BookSortOrder = BookSortOrder.LastPlayed,
    /**
     * PRODUCT_SPEC SET-002 — whether this screen lists libraries instead of books.
     *
     * A setting rather than a mode toggle in the app bar: it changes what the app opens on, which is a
     * preference, and a preference that resets when the process dies is not one.
     */
    val showsLibraries: Boolean = false,
    val isRefreshing: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.NeverSynced,
    val error: AppError? = null,
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
