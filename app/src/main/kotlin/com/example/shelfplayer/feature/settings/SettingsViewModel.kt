package com.example.shelfplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.StorageDiagnostics
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.domain.repository.DiagnosticsRepository
import com.example.shelfplayer.domain.repository.PreferencesRepository
import com.example.shelfplayer.domain.usecase.ObserveLibrariesUseCase
import com.example.shelfplayer.domain.usecase.ObserveServerDiagnosticsUseCase
import com.example.shelfplayer.domain.usecase.ServerDiagnostics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    observeServerDiagnostics: ObserveServerDiagnosticsUseCase,
    private val preferences: PreferencesRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        observeLibraries(),
        diagnostics.observeStorage(),
        preferences.observePreferences(),
        observeServerDiagnostics(),
    ) { libraries, storage, stored, server ->
        SettingsUiState(
            libraries = libraries,
            storage = storage,
            // PRODUCT_SPEC 6.1 step 9 — resolved against the granted libraries rather than shown raw.
            // A default library the profile has since lost is not a default any more, and rendering the
            // stored id would put a tick beside a library that is no longer in the list.
            defaultLibraryId = stored.defaultLibraryId?.takeIf { id -> libraries.any { it.id == id } },
            server = server,
            isLoaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        // PRODUCT_SPEC 16.3: no unbounded collection in a lifecycle owner.
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SettingsUiState(),
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
    /** PRODUCT_SPEC 6.1 step 9 — `null` is every granted library, which is the default. */
    val defaultLibraryId: LibraryId? = null,
    /** PRODUCT_SPEC SYNC-001 — what the handshake learned, or `null` while no profile is active. */
    val server: ServerDiagnostics? = null,
    val isLoaded: Boolean = false,
)
