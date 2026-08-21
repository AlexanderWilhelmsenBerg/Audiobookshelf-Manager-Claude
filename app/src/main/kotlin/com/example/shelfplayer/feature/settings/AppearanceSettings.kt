package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import com.example.shelfplayer.R
import com.example.shelfplayer.core.datastore.ThemeMode
import com.example.shelfplayer.core.model.settings.AppLanguage

/**
 * PRODUCT_SPEC SET-002 (Appearance/accessibility) — theme, colours and language.
 *
 * ### Why this is on the About tab
 *
 * Because it is about the app rather than about a server, a book or how playback behaves — the same
 * reasoning that put the launcher icon here, and it sits directly above it for that reason.
 *
 * ### Why the theme was already stored and had no control
 *
 * `AppSettings.theme_mode` has existed since the first build and `MainActivity` has always applied it, but
 * nothing ever wrote it: the setting was reachable only by a device that had one from an earlier version.
 * That is the shape of bug this section fixes — a preference that works perfectly and cannot be chosen.
 */
internal fun LazyListScope.appearanceSection(state: AppearanceUiState, actions: AppearanceActions) {
    item { SectionHeader(text = stringResource(R.string.settings_section_appearance)) }

    item {
        ChoiceRow(
            options = THEME_MODES,
            selected = state.themeMode.orSystem(),
            label = { mode -> stringResource(mode.labelRes()) },
            onSelected = actions.onThemeModeChanged,
        )
    }
    item { Hint(text = stringResource(R.string.settings_theme_hint)) }

    item {
        SwitchRow(
            label = stringResource(R.string.settings_dynamic_color),
            checked = state.dynamicColor,
            onCheckedChange = actions.onDynamicColorChanged,
        )
    }
    item { Hint(text = stringResource(R.string.settings_dynamic_color_hint)) }

    item { SubHeader(text = stringResource(R.string.settings_language)) }
    item {
        ChoiceRow(
            options = AppLanguage.entries,
            selected = state.language,
            label = { language -> language.label() },
            onSelected = actions.onLanguageChanged,
        )
    }
    item { Hint(text = stringResource(R.string.settings_language_hint)) }
}

/**
 * The three the user can choose between.
 *
 * `THEME_MODE_UNSPECIFIED` and `UNRECOGNIZED` are deliberately absent: the first is proto3's zero value and
 * the second is what a newer build's value reads back as. Neither is a choice, and both mean *follow the
 * system* — which [orSystem] is where that is said.
 */
private val THEME_MODES = listOf(
    ThemeMode.THEME_MODE_SYSTEM,
    ThemeMode.THEME_MODE_LIGHT,
    ThemeMode.THEME_MODE_DARK,
)

/** So a device that has never written the setting shows *Follow the system* selected rather than nothing. */
private fun ThemeMode.orSystem(): ThemeMode = if (this in THEME_MODES) this else ThemeMode.THEME_MODE_SYSTEM

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.THEME_MODE_LIGHT -> R.string.settings_theme_light
    ThemeMode.THEME_MODE_DARK -> R.string.settings_theme_dark
    // Every remaining value means "follow the system", and they are listed rather than collapsed into an
    // `else` so that a value added to the proto has to be considered here instead of silently reading as
    // System. `UNRECOGNIZED` is what protobuf gives a value written by a newer build.
    ThemeMode.THEME_MODE_SYSTEM,
    ThemeMode.THEME_MODE_UNSPECIFIED,
    ThemeMode.UNRECOGNIZED,
    -> R.string.settings_theme_system
}

/**
 * A language's own name, or the translated *Follow the system*.
 *
 * `AppLanguage.displayName` explains why the names are not translated: a reader who cannot read the
 * language the app is currently in still has to be able to find the one they can.
 */
@Composable
private fun AppLanguage.label(): String = displayName ?: stringResource(R.string.settings_language_system)

/**
 * PRODUCT_SPEC SET-002 — the three writes this section makes.
 *
 * A bundle for the reason every other settings bundle exists: three callbacks passed individually push
 * `SettingsScreen`'s parameter list past the point where the argument order is safe to get right.
 */
@Immutable
data class AppearanceActions(
    val onThemeModeChanged: (ThemeMode) -> Unit = {},
    val onDynamicColorChanged: (Boolean) -> Unit = {},
    val onLanguageChanged: (AppLanguage) -> Unit = {},
)
