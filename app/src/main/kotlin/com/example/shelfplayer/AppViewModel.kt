package com.example.shelfplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.datastore.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * PRODUCT_SPEC SET-002 (Appearance) — the app-wide appearance state.
 *
 * Held in a ViewModel rather than read inline in the Activity so that the theme survives
 * configuration changes without re-reading DataStore on every recomposition.
 */
@HiltViewModel
class AppViewModel @Inject constructor(settings: AppSettingsDataSource) : ViewModel() {
    val state: StateFlow<AppUiState> = settings.settings
        .map { stored ->
            AppUiState(
                themeMode = stored.themeMode,
                dynamicColor = stored.dynamicColor,
            )
        }
        .stateIn(
            scope = viewModelScope,
            // PRODUCT_SPEC 16.3: no unbounded collection in a lifecycle owner. The upstream is
            // dropped five seconds after the last collector goes away, which survives a rotation
            // without keeping a DataStore read alive for a backgrounded process.
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = AppUiState(),
        )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

data class AppUiState(val themeMode: ThemeMode = ThemeMode.THEME_MODE_SYSTEM, val dynamicColor: Boolean = false) {
    /**
     * `THEME_MODE_UNSPECIFIED` is the protobuf zero value, which a device that has never written a
     * setting will read. It is treated as "follow the system", the product default.
     */
    fun resolveDarkTheme(systemInDarkTheme: Boolean): Boolean = when (themeMode) {
        ThemeMode.THEME_MODE_DARK -> true
        ThemeMode.THEME_MODE_LIGHT -> false
        ThemeMode.THEME_MODE_SYSTEM,
        ThemeMode.THEME_MODE_UNSPECIFIED,
        ThemeMode.UNRECOGNIZED,
        -> systemInDarkTheme
    }
}
