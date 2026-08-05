package com.example.shelfplayer.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.ObserveLibrariesUseCase
import com.example.shelfplayer.domain.usecase.ObserveSyncStateUseCase
import com.example.shelfplayer.domain.usecase.RefreshLibraryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PRODUCT_SPEC LIB-001 — home renders cached content and shows sync status without blocking on it.
 *
 * The ViewModel never touches the gateway (PRODUCT_SPEC 22.8): it observes Room through use cases
 * and asks a use case to refresh.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    observeLibraries: ObserveLibrariesUseCase,
    observeSyncState: ObserveSyncStateUseCase,
    profileRepository: ProfileRepository,
    private val refreshLibrary: RefreshLibraryUseCase,
) : ViewModel() {

    private val refreshState = MutableStateFlow(RefreshState())

    val uiState: StateFlow<HomeUiState> = combine(
        profileRepository.observeActiveProfile(),
        observeLibraries(),
        observeSyncState(),
        refreshState,
    ) { profile, libraries, syncState, refresh ->
        HomeUiState(
            profile = profile,
            libraries = libraries,
            isRefreshing = refresh.inFlight,
            syncStatus = when {
                refresh.inFlight -> SyncStatus.Syncing
                // A failure from the user's own refresh outranks the persisted state, the same way the
                // error field does: it is the newer fact and the one they are waiting on.
                refresh.lastError != null -> SyncStatus.Failed
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
            error = refresh.lastError ?: syncState?.lastError,
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

    fun dismissError() {
        refreshState.update { it.copy(lastError = null) }
    }

    private data class RefreshState(val inFlight: Boolean = false, val lastError: AppError? = null)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** PRODUCT_SPEC 21 — loading, empty, error and content are separate, observable states. */
data class HomeUiState(
    val profile: Profile? = null,
    val libraries: List<Library> = emptyList(),
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
