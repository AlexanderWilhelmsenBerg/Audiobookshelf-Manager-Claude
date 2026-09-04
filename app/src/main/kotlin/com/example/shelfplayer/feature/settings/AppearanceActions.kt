package com.example.shelfplayer.feature.settings

import androidx.compose.runtime.Immutable
import com.example.shelfplayer.core.model.settings.AccentScheme
import com.example.shelfplayer.core.model.settings.AppLanguage
import com.example.shelfplayer.core.model.settings.GlassTint
import com.example.shelfplayer.core.model.settings.TextContrast
import com.example.shelfplayer.core.model.settings.ThemeChoice

/**
 * PRODUCT_SPEC SET-002 — the writes the Appearance tab makes.
 *
 * A bundle for the reason every other settings bundle exists: nine callbacks passed individually push
 * `SettingsScreen`'s parameter list past the point where the argument order is safe to get right.
 *
 * The section this file used to hold is now `appearanceTab` — it outgrew being three controls above the
 * launcher icon on the About tab, which is where a reader was least likely to look for the theme.
 */
@Immutable
data class AppearanceActions(
    /** PRODUCT_SPEC SET-002 — one of the app's own themes, or a bundled pack. See `ThemeChoice`. */
    val onThemeChoiceChanged: (ThemeChoice) -> Unit = {},
    val onAccentChanged: (AccentScheme) -> Unit = {},
    val onGlassTintChanged: (GlassTint) -> Unit = {},
    val onCardGlassTintChanged: (Boolean) -> Unit = {},
    val onSystemGlassTintChanged: (Boolean) -> Unit = {},
    val onTextContrastChanged: (TextContrast) -> Unit = {},
    /** In dp. Zero is a real choice — see `GlassBlur` for why it cannot be stored as a plain zero. */
    val onGlassBlurChanged: (Int) -> Unit = {},
    val onDynamicColorChanged: (Boolean) -> Unit = {},
    val onLanguageChanged: (AppLanguage) -> Unit = {},
)
