package com.example.shelfplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.datastore.ThemeMode
import com.example.shelfplayer.core.model.settings.AccentColor
import com.example.shelfplayer.core.model.settings.AppLanguage
import com.example.shelfplayer.core.model.settings.AppTheme
import com.example.shelfplayer.core.model.settings.GlassTint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PRODUCT_SPEC SET-002 (Appearance/accessibility) — how the app looks and which language it speaks.
 *
 * ### Why this is not part of `SettingsViewModel`
 *
 * Two reasons, and the second is the one that decided it.
 *
 * `SettingsViewModel` combines nine collaborators, which is detekt's constructor limit, so a tenth would
 * have to be folded into a bundle it has nothing to do with — the appearance store is not a device reading
 * and not a playback preference.
 *
 * More importantly, **appearance is not the settings screen's state.** `MainActivity` reads the same three
 * values to resolve the theme and the locale before any screen exists, through `AppViewModel`. A setting
 * whose reader is the activity and whose writer is a tab is a setting with two owners; giving the writer
 * its own small ViewModel keeps `SettingsViewModel` about the screen and leaves the appearance store with
 * one reader per consumer. `EventLogViewModel` is here for the same reason.
 *
 * ### Why it talks to the store directly
 *
 * There is nothing for a repository to mediate. These three values are device-wide, they are written and
 * read in the same process, and none of them is account-scoped or has a network or filesystem half —
 * `AppViewModel` reads the store the same way for the same reason.
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(private val settings: AppSettingsDataSource) : ViewModel() {

    val state: StateFlow<AppearanceUiState> = settings.settings
        .map { stored ->
            AppearanceUiState(
                // The key when there is one, and the older brightness when there is not — the same
                // reconciliation `AppUiState.resolveTheme` makes, and for the same reason: a reader who
                // chose Dark before this tab existed must not be moved to *follow the system* by an update.
                theme = if (stored.appThemeKey.isNotEmpty()) {
                    AppTheme.ofKey(stored.appThemeKey)
                } else {
                    stored.themeMode.asAppTheme()
                },
                accent = AccentColor.ofKey(stored.accentColorKey),
                glassTint = GlassTint.ofKey(stored.glassTintKey),
                cardGlassTintEnabled = !stored.cardGlassTintDisabled,
                systemGlassTintEnabled = !stored.systemGlassTintDisabled,
                dynamicColor = stored.dynamicColor,
                language = AppLanguage.ofTag(stored.appLanguageTag),
            )
        }
        .stateIn(
            scope = viewModelScope,
            // PRODUCT_SPEC 16.3 — no unbounded collection in a lifecycle owner.
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = AppearanceUiState(),
        )

    fun onThemeChanged(theme: AppTheme) {
        viewModelScope.launch { settings.setAppTheme(theme) }
    }

    fun onAccentChanged(accent: AccentColor) {
        viewModelScope.launch { settings.setAccentColor(accent) }
    }

    fun onGlassTintChanged(tint: GlassTint) {
        viewModelScope.launch { settings.setGlassTint(tint) }
    }

    fun onCardGlassTintChanged(enabled: Boolean) {
        viewModelScope.launch { settings.setCardGlassTintEnabled(enabled) }
    }

    fun onSystemGlassTintChanged(enabled: Boolean) {
        viewModelScope.launch { settings.setSystemGlassTintEnabled(enabled) }
    }

    fun onDynamicColorChanged(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(enabled) }
    }

    /**
     * PRODUCT_SPEC SET-002 — takes effect on the next frame, and never interrupts playback.
     *
     * Writing the setting is all this does. `AppLocale` observes it and re-composes the app in the new
     * language; on API 33 and above it also hands the choice to the platform, which recreates the activity.
     * Neither touches the media session, so a book keeps playing through a language change — product
     * priority 1.
     */
    fun onLanguageChanged(language: AppLanguage) {
        viewModelScope.launch { settings.setAppLanguage(language) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * PRODUCT_SPEC SET-002 — everything the Appearance tab draws and writes.
 *
 * @property isDark which ground the swatches should preview themselves against. [AppTheme.System] cannot
 *   answer it — only the device can — so the screen supplies the answer and this carries the default that
 *   matches the shipped palette. See `appearanceTab`.
 */
data class AppearanceUiState(
    val theme: AppTheme = AppTheme.Default,
    val accent: AccentColor = AccentColor.Default,
    val glassTint: GlassTint = GlassTint.Default,
    val cardGlassTintEnabled: Boolean = true,
    val systemGlassTintEnabled: Boolean = true,
    val dynamicColor: Boolean = false,
    val language: AppLanguage = AppLanguage.System,
    val isDark: Boolean = false,
)

/**
 * The older stored brightness, as a theme.
 *
 * Every value that is not explicitly light or dark means *follow the system*, and they are listed rather
 * than collapsed into an `else` so that a value added to the proto has to be considered here.
 * `UNRECOGNIZED` is what protobuf gives a value written by a newer build.
 */
private fun ThemeMode.asAppTheme(): AppTheme = when (this) {
    ThemeMode.THEME_MODE_LIGHT -> AppTheme.Light
    ThemeMode.THEME_MODE_DARK -> AppTheme.Dark
    ThemeMode.THEME_MODE_SYSTEM,
    ThemeMode.THEME_MODE_UNSPECIFIED,
    ThemeMode.UNRECOGNIZED,
    -> AppTheme.System
}
