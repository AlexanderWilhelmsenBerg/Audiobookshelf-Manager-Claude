package com.example.shelfplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.domain.repository.PreferencesRepository
import com.example.shelfplayer.domain.usecase.ObserveLibrariesUseCase
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
 * - **Libraries.** The one real preference: which library the shelf opens on. Tapping a row stars it.
 * - **About.** One row, opening the readings: the app's version, what the capability handshake learned,
 *   and the storage counts. They used to sit on this screen and a device run was right that they did
 *   not belong — a preference and a diagnostic are different kinds of thing, and mixing them puts a
 *   screenful of numbers between the user and the one choice here.
 *
 * Real preferences arrive with the behaviour that honours them. A screen full of switches that change
 * nothing is worse than a short one: it tells the user the app does something it does not.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    observeLibraries: ObserveLibrariesUseCase,
    private val preferences: PreferencesRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        observeLibraries(),
        preferences.observePreferences(),
    ) { libraries, stored ->
        SettingsUiState(
            libraries = libraries,
            // PRODUCT_SPEC 6.1 step 9 — resolved against the granted libraries rather than shown raw.
            // A default library the profile has since lost is not a default any more, and rendering the
            // stored id would put a tick beside a library that is no longer in the list.
            defaultLibraryId = stored.defaultLibraryId?.takeIf { id -> libraries.any { it.id == id } },
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
    /** PRODUCT_SPEC 6.1 step 9 — `null` is every granted library, which is the default. */
    val defaultLibraryId: LibraryId? = null,
    val isLoaded: Boolean = false,
)
