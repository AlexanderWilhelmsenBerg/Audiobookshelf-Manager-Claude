package com.example.shelfplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.settings.AccentScheme
import com.example.shelfplayer.core.model.settings.AppLanguage
import com.example.shelfplayer.core.model.settings.AppTheme
import com.example.shelfplayer.core.model.settings.BackgroundTheme
import com.example.shelfplayer.core.model.settings.GlassBlur
import com.example.shelfplayer.core.model.settings.GlassTint
import com.example.shelfplayer.core.model.settings.TextContrast
import com.example.shelfplayer.domain.repository.AppearanceRepository
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
 * Storage compatibility is resolved by [AppearanceRepository]. This shell sees only domain values, so a
 * protobuf field can change without presentation learning a second interpretation of it.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    appearance: AppearanceRepository,
    profileRepository: ProfileRepository,
) : ViewModel() {
    val state: StateFlow<AppUiState> = combine(
        appearance.observeAppearance(),
        profileRepository.observeProfiles(),
        profileRepository.observeServers(),
    ) { look, profiles, servers ->
        AppUiState(
            theme = look.theme,
            accent = look.accent,
            glassTint = look.glassTint,
            cardGlassTintEnabled = look.cardGlassTintEnabled,
            systemGlassTintEnabled = look.systemGlassTintEnabled,
            textContrast = look.textContrast,
            glassBlurDp = look.glassBlurDp,
            backgroundTheme = look.backgroundTheme,
            dynamicColor = look.dynamicColor,
            language = look.language,
            serverBaseUrls = servers.associate { it.id to it.baseUrl },
            hasAnyProfile = profiles.isNotEmpty(),
            isResolved = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = AppUiState(),
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * @property hasAnyProfile whether the app has a saved profile to show. Decides the start destination.
 * @property isResolved whether profile/storage state has been resolved yet.
 */
data class AppUiState(
    val theme: AppTheme = AppTheme.Default,
    val accent: AccentScheme = AccentScheme.Default,
    val glassTint: GlassTint = GlassTint.Default,
    val cardGlassTintEnabled: Boolean = true,
    val systemGlassTintEnabled: Boolean = true,
    val textContrast: TextContrast = TextContrast.Default,
    val glassBlurDp: Int = GlassBlur.DEFAULT_DP,
    val backgroundTheme: BackgroundTheme? = null,
    val dynamicColor: Boolean = false,
    val language: AppLanguage = AppLanguage.System,
    val serverBaseUrls: Map<ServerId, String> = emptyMap(),
    val hasAnyProfile: Boolean = false,
    val isResolved: Boolean = false,
) {
    fun resolveDarkTheme(systemInDarkTheme: Boolean): Boolean = theme.isDark ?: systemInDarkTheme

    fun resolveTheme(): AppTheme = theme

    /**
     * The accent to impose, or `null` when the selected pack already carries the authored pair currently in
     * force. This remains presentation logic because it answers how the resolved domain values are drawn.
     */
    fun accentArgbFor(isDark: Boolean): Long? {
        val pack = backgroundTheme
        if (pack != null && accent.belongsTo(pack)) return null
        return accent.argbFor(isDark)
    }
}
