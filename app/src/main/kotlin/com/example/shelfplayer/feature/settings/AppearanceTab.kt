package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.settings.AccentColor
import com.example.shelfplayer.core.model.settings.AppLanguage
import com.example.shelfplayer.core.model.settings.AppTheme
import com.example.shelfplayer.core.model.settings.GlassBlur
import com.example.shelfplayer.core.model.settings.GlassTint
import com.example.shelfplayer.core.model.settings.TextContrast
import com.example.shelfplayer.ui.glass.GlassCard
import kotlin.math.roundToInt

/**
 * PRODUCT_SPEC SET-002 (Appearance/accessibility) — the tab that owns how the app looks.
 *
 * ### Why this is a tab and no longer a section of About
 *
 * It was three controls tucked above the launcher icon, on the tab for *what this app is*. It is now the
 * theme, the accent, the glass and the language, and a reader looking for any of them was looking on the
 * wrong tab. It is also the first tab, because it is the one somebody opens Settings to change.
 *
 * ### Why the groups are cards
 *
 * Because the choices are of different kinds and a flat list of them reads as one long undifferentiated
 * column — which is what this was. A card per group says *these belong together* without a heading having
 * to say it, and the cards are the same glass as the shelf's, so the tab is also a live demonstration of
 * the two switches inside it.
 */
internal fun LazyListScope.appearanceTab(state: AppearanceUiState, actions: AppearanceActions) {
    // The tab's own name, on the tab. Its label in the row above is an icon now — *Appearance* is a long
    // word and four of them left no tab wide enough to read — so without this the screen would be the
    // only one in the app that never says what it is.
    item { TabHeading(text = stringResource(R.string.settings_section_appearance)) }
    themeGroup(state, actions)
    colourGroup(state, actions)
    glassGroup(state, actions)
    languageGroup(state, actions)
}

/** The look itself, plus the note AMOLED carries because it does more than darken. */
private fun LazyListScope.themeGroup(state: AppearanceUiState, actions: AppearanceActions) {
    item { SectionHeader(text = stringResource(R.string.settings_section_theme)) }
    item {
        SettingsGroup {
            ChoiceRow(
                options = AppTheme.entries,
                selected = state.theme,
                label = { theme -> stringResource(theme.labelRes()) },
                onSelected = actions.onThemeChanged,
            )
            Hint(text = stringResource(R.string.settings_theme_hint))
            if (state.theme == AppTheme.Amoled) {
                Hint(text = stringResource(R.string.settings_theme_amoled_hint))
            }
        }
    }
}

/** The accent, the tint it can lend the glass, and the wallpaper colours that override both. */
private fun LazyListScope.colourGroup(state: AppearanceUiState, actions: AppearanceActions) {
    item { SectionHeader(text = stringResource(R.string.settings_section_colour)) }
    item {
        SettingsGroup {
            SubHeader(text = stringResource(R.string.settings_accent_colour))
            SwatchRow(
                options = AccentColor.entries,
                selected = state.accent,
                // The tone for the ground the reader is actually looking at, so the swatch is the colour
                // they will get rather than the other half of the pair.
                colorOf = { accent -> Color(accent.argbFor(state.isDark)) },
                labelOf = { accent -> stringResource(accent.labelRes()) },
                onSelected = actions.onAccentChanged,
            )
            Hint(text = stringResource(R.string.settings_accent_colour_hint))

            SubHeader(text = stringResource(R.string.settings_tint_colour))
            SwatchRow(
                options = GlassTint.entries,
                selected = state.glassTint,
                colorOf = { tint -> Color(tint.argbOr(state.accent.argbFor(state.isDark))) },
                labelOf = { tint -> stringResource(tint.labelRes()) },
                onSelected = actions.onGlassTintChanged,
            )
            Hint(text = stringResource(R.string.settings_tint_colour_hint))

            SubHeader(text = stringResource(R.string.settings_text_contrast))
            ChoiceRow(
                options = TextContrast.entries,
                selected = state.textContrast,
                label = { contrast -> stringResource(contrast.labelRes()) },
                onSelected = actions.onTextContrastChanged,
            )
            Hint(text = stringResource(R.string.settings_text_contrast_hint))

            SwitchRow(
                label = stringResource(R.string.settings_dynamic_color),
                checked = state.dynamicColor,
                onCheckedChange = actions.onDynamicColorChanged,
            )
            Hint(text = stringResource(R.string.settings_dynamic_color_hint))
        }
    }
}

/** The two wash switches, and the note that they do nothing where there is no blur to wash over. */
private fun LazyListScope.glassGroup(state: AppearanceUiState, actions: AppearanceActions) {
    item { SectionHeader(text = stringResource(R.string.settings_section_glass)) }
    item {
        SettingsGroup {
            SliderRow(
                label = stringResource(R.string.settings_blur),
                valueLabel = if (state.glassBlurDp <= 0) {
                    stringResource(R.string.settings_blur_off)
                } else {
                    stringResource(R.string.settings_blur_value, state.glassBlurDp)
                },
                value = state.glassBlurDp.toFloat(),
                range = 0f..GlassBlur.MAX_DP.toFloat(),
                // The count *between* the ends, so whole dp across 0..MAX is one less than the span.
                steps = GlassBlur.MAX_DP - 1,
                onValueChange = { dp -> actions.onGlassBlurChanged(dp.roundToInt()) },
            )
            Hint(text = stringResource(R.string.settings_blur_hint))
            SwitchRow(
                label = stringResource(R.string.settings_card_tint),
                checked = state.cardGlassTintEnabled,
                onCheckedChange = actions.onCardGlassTintChanged,
            )
            Hint(text = stringResource(R.string.settings_card_tint_hint))
            SwitchRow(
                label = stringResource(R.string.settings_system_tint),
                checked = state.systemGlassTintEnabled,
                onCheckedChange = actions.onSystemGlassTintChanged,
            )
            Hint(text = stringResource(R.string.settings_system_tint_hint))
            Hint(text = stringResource(R.string.settings_glass_no_blur_note))
        }
    }
}

private fun LazyListScope.languageGroup(state: AppearanceUiState, actions: AppearanceActions) {
    item { SectionHeader(text = stringResource(R.string.settings_language)) }
    item {
        SettingsGroup {
            ChoiceRow(
                options = AppLanguage.entries,
                selected = state.language,
                label = { language -> language.label() },
                onSelected = actions.onLanguageChanged,
            )
            Hint(text = stringResource(R.string.settings_language_hint))
        }
    }
}

/**
 * One group of settings, on the same glass the shelf's cards are made of.
 *
 * The horizontal inset is on the card rather than on its rows: the rows already pad themselves to 16dp
 * and doubling that inside a card leaves the controls floating in the middle of it.
 */
@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) { content() }
    }
}

/**
 * A row of colours, one of which is chosen.
 *
 * ### Why each swatch carries its name
 *
 * A colour is not a label. Without [labelOf] on the semantics this row is six identical unnamed circles
 * to a screen reader and unusable to anyone who cannot distinguish them by eye — which is the group most
 * likely to be in an appearance screen changing the colours in the first place. `Role.RadioButton` and
 * the selected state come from `selectable`, so the reader is told which one is on as well.
 *
 * The ring is drawn rather than an overlaid check mark, because a mark's own colour would have to contrast
 * with every swatch and one of them would always lose.
 */
@Composable
private fun <T> SwatchRow(
    options: List<T>,
    selected: T,
    colorOf: (T) -> Color,
    labelOf: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val label = labelOf(option)
            Box(
                modifier = Modifier
                    .size(SWATCH_TOUCH_TARGET)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelected(option) },
                    )
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(SWATCH_SIZE)
                        .background(color = colorOf(option), shape = CircleShape)
                        .border(
                            width = if (isSelected) SWATCH_RING_WIDTH else SWATCH_EDGE_WIDTH,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

private fun AppTheme.labelRes(): Int = when (this) {
    AppTheme.System -> R.string.settings_theme_system
    AppTheme.Light -> R.string.settings_theme_light
    AppTheme.Dark -> R.string.settings_theme_dark
    AppTheme.Amoled -> R.string.settings_theme_amoled
}

private fun AccentColor.labelRes(): Int = when (this) {
    AccentColor.Teal -> R.string.settings_accent_teal
    AccentColor.Indigo -> R.string.settings_accent_indigo
    AccentColor.Plum -> R.string.settings_accent_plum
    AccentColor.Ember -> R.string.settings_accent_ember
    AccentColor.Moss -> R.string.settings_accent_moss
    AccentColor.Slate -> R.string.settings_accent_slate
}

private fun TextContrast.labelRes(): Int = when (this) {
    TextContrast.Automatic -> R.string.settings_contrast_auto
    TextContrast.High -> R.string.settings_contrast_high
    TextContrast.Soft -> R.string.settings_contrast_soft
}

private fun GlassTint.labelRes(): Int = when (this) {
    GlassTint.White -> R.string.settings_tint_white
    GlassTint.Warm -> R.string.settings_tint_warm
    GlassTint.Cool -> R.string.settings_tint_cool
    GlassTint.FollowAccent -> R.string.settings_tint_accent
}

/**
 * A language's own name, or the translated *Follow the system*.
 *
 * `AppLanguage.displayName` explains why the names are not translated: a reader who cannot read the
 * language the app is currently in still has to be able to find the one they can.
 */
@Composable
private fun AppLanguage.label(): String = displayName ?: stringResource(R.string.settings_language_system)

/** 48dp, because a colour the size of the colour would be a target below the accessibility floor. */
private val SWATCH_TOUCH_TARGET = 48.dp
private val SWATCH_SIZE = 32.dp
private val SWATCH_RING_WIDTH = 3.dp
private val SWATCH_EDGE_WIDTH = 1.dp
