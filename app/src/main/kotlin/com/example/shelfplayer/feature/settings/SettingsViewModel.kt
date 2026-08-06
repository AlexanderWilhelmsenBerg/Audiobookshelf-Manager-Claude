package com.example.shelfplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.StorageDiagnostics
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.domain.repository.DiagnosticsRepository
import com.example.shelfplayer.domain.usecase.ObserveLibrariesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * PRODUCT_SPEC SET-001 / SET-002 — what the settings screen has to show today.
 *
 * Two sections, and neither is a preference:
 *
 * - **Libraries.** Browsing by library lives here. It was briefly a switch that turned the home screen
 *   into a list of libraries, which was worse in the way modal settings usually are: the user had to go to
 *   Settings, flip something, and navigate back to find out what it did. The libraries are simply listed,
 *   and tapping one opens it.
 * - **Storage.** The counts behind the acceptance checks that ask what was *stored* rather than what is
 *   shown (PRODUCT_SPEC SET-002, Privacy/diagnostics). They existed only as `adb … sqlite3` commands,
 *   which needs a cable and a device shipping `sqlite3`.
 *
 * Real preferences arrive with the behaviour that honours them. A screen full of switches that change
 * nothing is worse than a short one: it tells the user the app does something it does not.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    observeLibraries: ObserveLibrariesUseCase,
    diagnostics: DiagnosticsRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        observeLibraries(),
        diagnostics.observeStorage(),
    ) { libraries, storage ->
        SettingsUiState(libraries = libraries, storage = storage, isLoaded = true)
    }.stateIn(
        scope = viewModelScope,
        // PRODUCT_SPEC 16.3: no unbounded collection in a lifecycle owner.
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SettingsUiState(),
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * @property libraries the libraries the active profile is granted — the grant is applied by the
 *   repository, so this list is what the user may open, not what exists.
 * @property storage what is on disk, including rows outside that grant, as counts. The difference between
 *   the two is the point: it is how "unauthorized libraries were never written" becomes checkable.
 * @property isLoaded whether the first read has arrived. Zeroes before it has would read as facts.
 */
data class SettingsUiState(
    val libraries: List<Library> = emptyList(),
    val storage: StorageDiagnostics = StorageDiagnostics(),
    val isLoaded: Boolean = false,
)
