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
    profileRepository: ProfileRepository,
    private val refreshLibrary: RefreshLibraryUseCase,
) : ViewModel() {

    private val refreshState = MutableStateFlow(RefreshState())

    val uiState: StateFlow<HomeUiState> = combine(
        profileRepository.observeActiveProfile(),
        observeLibraries(),
        refreshState,
    ) { profile, libraries, refresh ->
        HomeUiState(
            profile = profile,
            libraries = libraries,
            isRefreshing = refresh.inFlight,
            syncStatus = when {
                refresh.inFlight -> SyncStatus.Syncing
                refresh.lastError != null -> SyncStatus.Failed
                libraries.isNotEmpty() -> SyncStatus.Succeeded
                else -> SyncStatus.NeverSynced
            },
            error = refresh.lastError,
            // PRODUCT_SPEC LIB-001: loading and empty are distinct. "Still seeding" is not "empty".
            isInitialLoad = profile == null && libraries.isEmpty() && refresh.lastError == null,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = HomeUiState(),
    )

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
    val isInitialLoad: Boolean = true,
)
