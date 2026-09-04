package com.example.shelfplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.datastore.ThemeMode
import com.example.shelfplayer.core.model.settings.AccentScheme
import com.example.shelfplayer.core.model.settings.AppLanguage
import com.example.shelfplayer.core.model.settings.AppTheme
import com.example.shelfplayer.core.model.settings.BackgroundTheme
import com.example.shelfplayer.core.model.settings.GlassBlur
import com.example.shelfplayer.core.model.settings.GlassTint
import com.example.shelfplayer.core.model.settings.TextContrast
import com.example.shelfplayer.domain.settings.BackgroundThemeCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
class AppearanceViewModel @Inject constructor(
    private val settings: AppSettingsDataSource,
    private val backgroundThemes: BackgroundThemeCatalog,
) : ViewModel() {

    /** The bundled packs. Read once — see `BackgroundThemeCatalog`; the catalog itself caches. */
    private val catalog: Flow<List<BackgroundTheme>> = flow { emit(backgroundThemes.themes()) }

    val state: StateFlow<AppearanceUiState> = settings.settings
        .combine(catalog) { stored, themes -> stored to themes }
        .map { (stored, themes) ->
            AppearanceUiState(
                // The key when there is one, and the older brightness when there is not — the same
                // reconciliation `AppUiState.resolveTheme` makes, and for the same reason: a reader who
                // chose Dark before this tab existed must not be moved to *follow the system* by an update.
                theme = if (stored.appThemeKey.isNotEmpty()) {
                    AppTheme.ofKey(stored.appThemeKey)
                } else {
                    stored.themeMode.asAppTheme()
                },
                accent = AccentScheme.ofKey(stored.accentColorKey, themes),
                glassTint = GlassTint.ofKey(stored.glassTintKey),
                cardGlassTintEnabled = !stored.cardGlassTintDisabled,
                systemGlassTintEnabled = !stored.systemGlassTintDisabled,
                textContrast = TextContrast.ofKey(stored.textContrastKey),
                // Through `GlassBlur.ofStored`, which is where *off* is told from *never chosen*.
                glassBlurDp = GlassBlur.ofStored(stored.glassBlurDp),
                backgroundThemes = themes,
                // Held as the id rather than the theme so that an id naming a pack this build no longer
                // ships shows as *None* instead of as a selected row the picker cannot draw.
                backgroundThemeId = stored.backgroundThemeId.takeIf { id -> themes.any { it.id == id } },
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

    fun onAccentChanged(accent: AccentScheme) {
        viewModelScope.launch { settings.setAccent(accent) }
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

    fun onTextContrastChanged(contrast: TextContrast) {
        viewModelScope.launch { settings.setTextContrast(contrast) }
    }

    fun onGlassBlurChanged(dp: Int) {
        viewModelScope.launch { settings.setGlassBlurDp(dp) }
    }

    /**
     * PRODUCT_SPEC SET-002 — the pack, and the accent that comes with it.
     *
     * Two writes rather than one, because *"picking a theme chooses the right scheme"* is a second fact
     * about the same press. `AccentScheme.following` owns which — including the case it declines: a reader
     * who deliberately chose *Plum* keeps it when they turn a pack off, and only an accent that came from
     * a pack is taken back to the default. The decision is a pure function so that rule is testable
     * without a screen.
     *
     * The accent is written **first**. Both land on the same `DataStore`, and writing the pack first would
     * publish one frame in which the new artwork is drawn under the old pack's colours.
     *
     * ### Why the current accent is read from the store and not from [state]
     *
     * Because [state] is `WhileSubscribed`, so `state.value` is the *initial* value — no pack list, and the
     * default accent — whenever nothing is collecting. From the screen there always is, which is exactly
     * what makes this the kind of coupling that holds until it does not: a press routed from anywhere else,
     * or a collector that had timed out, would silently deselect the pack and write nothing else.
     * `AppearanceViewModelTest` found it by driving the view model without a collector, which is the shape
     * the bug has. Reading the store answers the question the write is actually about.
     */
    fun onBackgroundThemeChanged(id: String?) {
        viewModelScope.launch {
            // The catalog caches, so this is a map lookup after the first call rather than a second read
            // of the assets. See `BackgroundThemeCatalog`.
            val themes = backgroundThemes.themes()
            val theme = id?.let { chosen -> themes.firstOrNull { it.id == chosen } }
            val current = AccentScheme.ofKey(settings.settings.first().accentColorKey, themes)
            AccentScheme.following(current, theme)?.let { next -> settings.setAccent(next) }
            settings.setBackgroundThemeId(theme?.id)
        }
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
    val accent: AccentScheme = AccentScheme.Default,
    val glassTint: GlassTint = GlassTint.Default,
    val cardGlassTintEnabled: Boolean = true,
    val systemGlassTintEnabled: Boolean = true,
    val textContrast: TextContrast = TextContrast.Default,
    val glassBlurDp: Int = GlassBlur.DEFAULT_DP,
    /** PRODUCT_SPEC SET-002 — every bundled pack, for the picker. Empty if the assets are unreadable. */
    val backgroundThemes: List<BackgroundTheme> = emptyList(),
    val backgroundThemeId: String? = null,
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
