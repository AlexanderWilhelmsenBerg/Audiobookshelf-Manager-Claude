package com.example.shelfplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.settings.AccentScheme
import com.example.shelfplayer.core.model.settings.AppLanguage
import com.example.shelfplayer.core.model.settings.AppTheme
import com.example.shelfplayer.core.model.settings.BackgroundTheme
import com.example.shelfplayer.core.model.settings.GlassBlur
import com.example.shelfplayer.core.model.settings.GlassTint
import com.example.shelfplayer.core.model.settings.TextContrast
import com.example.shelfplayer.core.model.settings.ThemeChoice
import com.example.shelfplayer.domain.repository.AppearanceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PRODUCT_SPEC SET-002 — the Appearance tab as a presentation projection of [AppearanceRepository].
 *
 * The repository owns DataStore compatibility, bundled-theme resolution and the ordered multi-write needed
 * when one theme choice changes both the plain theme layer and its following accent.
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val appearance: AppearanceRepository,
) : ViewModel() {

    val state: StateFlow<AppearanceUiState> = appearance.observeAppearance()
        .map { look ->
            AppearanceUiState(
                theme = look.theme,
                accent = look.accent,
                glassTint = look.glassTint,
                cardGlassTintEnabled = look.cardGlassTintEnabled,
                systemGlassTintEnabled = look.systemGlassTintEnabled,
                textContrast = look.textContrast,
                glassBlurDp = look.glassBlurDp,
                backgroundThemes = look.backgroundThemes,
                backgroundThemeId = look.backgroundThemeId,
                dynamicColor = look.dynamicColor,
                language = look.language,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = AppearanceUiState(),
        )

    fun onAccentChanged(accent: AccentScheme) {
        viewModelScope.launch { appearance.setAccent(accent) }
    }

    fun onGlassTintChanged(tint: GlassTint) {
        viewModelScope.launch { appearance.setGlassTint(tint) }
    }

    fun onCardGlassTintChanged(enabled: Boolean) {
        viewModelScope.launch { appearance.setCardGlassTintEnabled(enabled) }
    }

    fun onSystemGlassTintChanged(enabled: Boolean) {
        viewModelScope.launch { appearance.setSystemGlassTintEnabled(enabled) }
    }

    fun onTextContrastChanged(contrast: TextContrast) {
        viewModelScope.launch { appearance.setTextContrast(contrast) }
    }

    fun onGlassBlurChanged(dp: Int) {
        viewModelScope.launch { appearance.setGlassBlurDp(dp) }
    }

    /**
     * One press is one repository operation. The data layer keeps the existing write order so switching from
     * a pack to a plain theme still avoids a visible frame of the old theme and pack-derived accents follow
     * exactly the same policy as before.
     */
    fun onThemeChoiceChanged(choice: ThemeChoice) {
        viewModelScope.launch { appearance.setThemeChoice(choice) }
    }

    fun onDynamicColorChanged(enabled: Boolean) {
        viewModelScope.launch { appearance.setDynamicColor(enabled) }
    }

    fun onLanguageChanged(language: AppLanguage) {
        viewModelScope.launch { appearance.setLanguage(language) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** PRODUCT_SPEC SET-002 — everything the Appearance tab draws. */
data class AppearanceUiState(
    val theme: AppTheme = AppTheme.Default,
    val accent: AccentScheme = AccentScheme.Default,
    val glassTint: GlassTint = GlassTint.Default,
    val cardGlassTintEnabled: Boolean = true,
    val systemGlassTintEnabled: Boolean = true,
    val textContrast: TextContrast = TextContrast.Default,
    val glassBlurDp: Int = GlassBlur.DEFAULT_DP,
    val backgroundThemes: List<BackgroundTheme> = emptyList(),
    val backgroundThemeId: String? = null,
    val dynamicColor: Boolean = false,
    val language: AppLanguage = AppLanguage.System,
    val isDark: Boolean = false,
)
