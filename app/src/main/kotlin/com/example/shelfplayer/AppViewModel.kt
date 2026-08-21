package com.example.shelfplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.datastore.ThemeMode
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.settings.AppLanguage
import com.example.shelfplayer.domain.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * PRODUCT_SPEC SET-002 (Appearance) — the app-wide appearance state.
 *
 * Held in a ViewModel rather than read inline in the Activity so that the theme survives
 * configuration changes without re-reading DataStore on every recomposition.
 */
@HiltViewModel
class AppViewModel @Inject constructor(settings: AppSettingsDataSource, profileRepository: ProfileRepository) :
    ViewModel() {
    val state: StateFlow<AppUiState> = combine(
        settings.settings,
        profileRepository.observeProfiles(),
        profileRepository.observeServers(),
    ) { stored, profiles, servers ->
        AppUiState(
            themeMode = stored.themeMode,
            dynamicColor = stored.dynamicColor,
            // PRODUCT_SPEC SET-002 — read here rather than in a screen because `AppLocale` wraps the whole
            // app: the language has to be resolved before the first string is drawn, not when Settings is
            // opened.
            language = AppLanguage.ofTag(stored.appLanguageTag),
            // PRODUCT_SPEC LIB-004 — the addresses cover URLs are built from. Held app-wide because a
            // cover is rendered on every shelf and the address belongs to the server row, not the book.
            serverBaseUrls = servers.associate { it.id to it.baseUrl },
            // PRODUCT_SPEC 6.1 / AUTH-002 — observed rather than read once, so that removing the last
            // profile sends the user back to onboarding instead of leaving them on an unusable home.
            hasAnyProfile = profiles.isNotEmpty(),
            isResolved = true,
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

/**
 * @property hasAnyProfile whether the app has a saved profile to show. Decides the start destination.
 * @property isResolved whether [hasAnyProfile] has been read from storage yet. The default is `false`
 *   because the two answers lead to different screens: composing the navigation graph before the answer is
 *   known would start it at sign-in and then have to correct itself, which a user sees as a flash of the
 *   wrong screen on every cold start.
 */
data class AppUiState(
    val themeMode: ThemeMode = ThemeMode.THEME_MODE_SYSTEM,
    val dynamicColor: Boolean = false,
    /** PRODUCT_SPEC SET-002 — the chosen language, or [AppLanguage.System] to follow the device. */
    val language: AppLanguage = AppLanguage.System,
    /** PRODUCT_SPEC LIB-004 — server id to base address, for resolving cover URLs. */
    val serverBaseUrls: Map<ServerId, String> = emptyMap(),
    val hasAnyProfile: Boolean = false,
    val isResolved: Boolean = false,
) {
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
