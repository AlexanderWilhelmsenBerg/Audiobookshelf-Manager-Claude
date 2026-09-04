package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.settings.AccentColor
import com.example.shelfplayer.core.model.settings.AccentScheme
import com.example.shelfplayer.core.model.settings.AppLanguage
import com.example.shelfplayer.core.model.settings.AppTheme
import com.example.shelfplayer.core.model.settings.BackgroundTheme
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
    backgroundThemeGroup(state, actions)
    themeGroup(state, actions)
    colourGroup(state, actions)
    glassGroup(state, actions)
    languageGroup(state, actions)
}

/**
 * PRODUCT_SPEC SET-002 — the bundled packs, as a list you open.
 *
 * ### Why a dropdown and not the row of thumbnails this used to be
 *
 * The row was six 104dp cells and it could only ever be scrolled sideways to reach the sixth — a
 * horizontal scroller nested in a vertical one, which is awkward with a thumb and worse with a switch or
 * a screen reader. A dropdown names every pack in one list that opens where the setting is, and it takes
 * one row of the tab instead of a fifth of it. The owner asked for the change; this is why it is also the
 * better control.
 *
 * **The picture is not lost.** Each row still carries its own artwork as a leading thumbnail, loaded from
 * the same asset the backdrop uses rather than an approximation that could drift from it, because
 * *Nebula Glow* on its own still tells a reader nothing.
 *
 * ### Why *None* is first
 *
 * It is the default and it is the way back. A picker whose only exits are six pictures is a picker you
 * cannot leave.
 */
private fun LazyListScope.backgroundThemeGroup(state: AppearanceUiState, actions: AppearanceActions) {
    if (state.backgroundThemes.isEmpty()) return
    item { SectionHeader(text = stringResource(R.string.settings_section_background)) }
    item {
        SettingsGroup {
            // `null` is a real option rather than a way of clearing the choice, so it is in the list.
            val options: List<BackgroundTheme?> = listOf(null) + state.backgroundThemes
            val none = stringResource(R.string.settings_background_none)
            DropdownRow(
                label = stringResource(R.string.settings_section_background),
                options = options,
                selected = options.firstOrNull { it?.id == state.backgroundThemeId },
                labelOf = { theme -> theme?.name ?: none },
                leadingOf = { theme -> ThemeThumbnail(assetPath = theme?.ground?.asset) },
                onSelected = { theme -> actions.onBackgroundThemeChanged(theme?.id) },
            )
            Hint(text = stringResource(R.string.settings_background_hint))
        }
    }
}

/**
 * One pack's artwork, small.
 *
 * Never named: it sits inside a row the dropdown has already labelled with the pack's name, and a picture
 * that announced itself as well would have a screen reader say the name twice.
 */
@Composable
private fun ThemeThumbnail(assetPath: String?) {
    val shape = RoundedCornerShape(THUMBNAIL_CORNER)
    Box(
        modifier = Modifier
            .size(width = THUMBNAIL_WIDTH, height = THUMBNAIL_HEIGHT)
            .clip(shape)
            .background(color = MaterialTheme.colorScheme.surfaceVariant, shape = shape)
            .border(width = THUMBNAIL_EDGE, color = MaterialTheme.colorScheme.outlineVariant, shape = shape),
        contentAlignment = Alignment.Center,
    ) {
        if (assetPath == null) {
            Icon(
                imageVector = Icons.Filled.FormatColorReset,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(THUMBNAIL_GLYPH),
            )
        } else {
            AsyncImage(
                model = "file:///android_asset/$assetPath",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
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
            DropdownRow(
                label = stringResource(R.string.settings_accent_colour),
                // The closed palette, then one entry per bundled pack — see `AccentScheme` for why a
                // pack's colours are choosable on their own and how both tones come from the pack itself.
                options = AccentScheme.all(state.backgroundThemes),
                selected = state.accent,
                labelOf = { accent -> accent.label(state.backgroundThemes) },
                // The tone for the ground the reader is actually looking at, so the swatch is the colour
                // they will get rather than the other half of the pair.
                leadingOf = { accent -> Swatch(color = Color(accent.argbFor(state.isDark))) },
                onSelected = actions.onAccentChanged,
            )
            Hint(text = stringResource(R.string.settings_accent_colour_hint))

            DropdownRow(
                label = stringResource(R.string.settings_tint_colour),
                options = GlassTint.entries,
                selected = state.glassTint,
                labelOf = { tint -> stringResource(tint.labelRes()) },
                leadingOf = { tint ->
                    Swatch(color = Color(tint.argbOr(state.accent.argbFor(state.isDark))))
                },
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
            DropdownRow(
                label = stringResource(R.string.settings_language),
                options = AppLanguage.entries,
                selected = state.language,
                labelOf = { language -> language.label() },
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
 * PRODUCT_SPEC SET-002 — one setting, chosen from a list that opens where the setting is.
 *
 * ### Why this replaced a row of swatches and three radio rows
 *
 * The swatch rows were six 48dp circles that had to scroll sideways inside a vertically scrolling tab, and
 * the radio rows spent a full line on every option whether or not anyone would ever pick it. Neither
 * scales: the accent list grew by one entry per bundled background pack the moment those landed. A
 * dropdown is one row per setting however long the list gets, and it puts the current value on screen —
 * which a row of unlabelled circles never did.
 *
 * ### The semantics, which are the whole reason this is hand-rolled
 *
 * The collapsed row announces itself as **"<setting>, <value>"** and nothing else: [label] and the value
 * are drawn as two `Text`s for the eye and both are silenced with `clearAndSetSemantics`, because a screen
 * reader hearing "Accent colour" and then "Teal" as two separate nodes has to assemble the sentence
 * itself. `Role.DropdownList` is what tells it the row opens something.
 *
 * A colour is not a label, so [leadingOf] is decorative by construction — every swatch and thumbnail this
 * draws passes `contentDescription = null`, and the name beside it is the label. That is the same rule the
 * thumbnails followed before, and it is why this control is usable by someone who cannot tell the colours
 * apart, which is a group with an unusually strong reason to be on an appearance screen.
 */
@Composable
private fun <T> DropdownRow(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    leadingOf: (@Composable (T) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = labelOf(selected)
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_HEIGHT)
                .clickable(role = Role.DropdownList) { expanded = true }
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { contentDescription = "$label, $current" },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(WEIGHT_FILL).clearAndSetSemantics { },
            )
            leadingOf?.invoke(selected)
            Text(
                text = current,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                // The row is already named and already says it is a dropdown; naming the arrow as well
                // would have a screen reader announce the same fact twice.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                val optionLabel = labelOf(option)
                DropdownMenuItem(
                    text = { Text(text = optionLabel) },
                    leadingIcon = leadingOf?.let { draw -> { draw(option) } },
                    // A tick rather than a highlight: the menu is drawn over the app's own glass, and a
                    // selected-row background would have to contrast with whatever artwork is behind it.
                    trailingIcon = if (option == selected) {
                        { Icon(imageVector = Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

/** A colour, as a circle. Decorative — the name beside it is what the row is labelled with. */
@Composable
private fun Swatch(color: Color) {
    Box(
        modifier = Modifier
            .size(SWATCH_SIZE)
            .background(color = color, shape = CircleShape)
            .border(width = SWATCH_EDGE_WIDTH, color = MaterialTheme.colorScheme.outlineVariant, shape = CircleShape),
    )
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

/**
 * An accent's name: the built-in's, or the pack the colours were authored for.
 *
 * A pack accent is named after its pack rather than after its hue, because *Teal Horizon* is what the
 * reader picked in the row above and a second word for the same colour would read as a different one.
 *
 * The fallback is unreachable through `AppearanceViewModel` — it resolves the accent against the same
 * [themes] the tab is given, so a key naming a pack that is not in the list has already become the
 * default. It is here rather than as a `!!` because a screen that cannot name its own value should say
 * something true and vague, not crash.
 */
@Composable
private fun AccentScheme.label(themes: List<BackgroundTheme>): String = when {
    isFromTheme -> themes.firstOrNull(::belongsTo)?.name ?: stringResource(R.string.settings_accent_theme)
    else -> stringResource(AccentColor.ofKey(key).labelRes())
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

/** A 3:2 crop, large enough that the artwork is recognisable beside a name and small enough for a row. */
private val THUMBNAIL_WIDTH = 42.dp
private val THUMBNAIL_HEIGHT = 28.dp
private val THUMBNAIL_CORNER = 6.dp
private val THUMBNAIL_EDGE = 1.dp
private val THUMBNAIL_GLYPH = 18.dp

private val SWATCH_SIZE = 24.dp
private val SWATCH_EDGE_WIDTH = 1.dp

/**
 * The floor for a dropdown row.
 *
 * 48dp is the platform's touch target and the figure `assertEveryControlIsBigEnough` measures against —
 * with 8dp of slack, because that assertion checks *visual* bounds and a row sized only by its content
 * would land under the line as soon as somebody shortened the text.
 */
private val ROW_MIN_HEIGHT = 48.dp

/** The label takes the row and the value sits at its end, as every other settings row is laid out. */
private const val WEIGHT_FILL = 1f
