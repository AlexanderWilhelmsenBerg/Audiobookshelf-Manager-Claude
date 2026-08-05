package com.example.shelfplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PRODUCT_SPEC SET-001 / SET-002 — the settings the app has so far.
 *
 * Only the ones a screen can actually honour are listed. A settings screen full of switches that change
 * nothing is worse than a short one: it tells the user the app does something it does not.
 *
 * The store is reached through [SettingsRepository] rather than named directly, so this screen can be
 * tested without a DataStore on disk and so the meaning of a setting — its default, its precedence —
 * belongs to `:data:settings` instead of to whichever screen happened to need it first.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = settings.homeShowsLibraries
        .map { showsLibraries -> SettingsUiState(homeShowsLibraries = showsLibraries, isLoaded = true) }
        .stateIn(
            scope = viewModelScope,
            // PRODUCT_SPEC 16.3: no unbounded collection in a lifecycle owner.
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SettingsUiState(),
        )

    fun onHomeShowsLibrariesChanged(enabled: Boolean) {
        viewModelScope.launch { settings.setHomeShowsLibraries(enabled) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * @property isLoaded whether the stored value has been read. The switch is disabled until it has, so a
 *   tap during the first frame cannot write the default back over what is on disk.
 */
data class SettingsUiState(val homeShowsLibraries: Boolean = false, val isLoaded: Boolean = false)
