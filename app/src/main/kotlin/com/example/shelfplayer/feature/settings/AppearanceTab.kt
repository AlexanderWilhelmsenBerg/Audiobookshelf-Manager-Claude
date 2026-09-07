package com.example.shelfplayer.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
import com.example.shelfplayer.core.designsystem.theme.dynamicAccentColor
import com.example.shelfplayer.core.model.settings.AccentColor
import com.example.shelfplayer.core.model.settings.AccentScheme
import com.example.shelfplayer.core.model.settings.AppLanguage
import com.example.shelfplayer.core.model.settings.AppTheme
import com.example.shelfplayer.core.model.settings.BackgroundTheme
import com.example.shelfplayer.core.model.settings.GlassBlur
import com.example.shelfplayer.core.model.settings.GlassTint
import com.example.shelfplayer.core.model.settings.TextContrast
import com.example.shelfplayer.core.model.settings.ThemeChoice
import com.example.shelfplayer.ui.glass.GlassCard
import kotlin.math.roundToInt

/** PRODUCT_SPEC SET-002 — the tab that owns the app's appearance and accessibility choices. */
internal fun LazyListScope.appearanceTab(state: AppearanceUiState, actions: AppearanceActions) {
    item { TabHeading(text = stringResource(R.string.settings_section_appearance)) }
    themeGroup(state, actions)
    colourGroup(state, actions)
    glassGroup(state, actions)
    languageGroup(state, actions)
}

/** Every look the app has, plain themes and bundled packs, in one inline list. */
private fun LazyListScope.themeGroup(state: AppearanceUiState, actions: AppearanceActions) {
    item { SectionHeader(text = stringResource(R.string.settings_section_theme)) }
    item {
        SettingsGroup {
            val selected = ThemeChoice.of(state.theme, state.backgroundThemeId, state.backgroundThemes)
            DropdownRow(
                label = stringResource(R.string.settings_section_theme),
                showLabel = false,
                options = ThemeChoice.all(state.backgroundThemes),
                selected = selected,
                labelOf = { choice -> choice.label() },
                leadingOf = { choice -> ThemeThumbnail(choice) },
                onSelected = actions.onThemeChoiceChanged,
            )
        }
    }
}

/** A theme as the ground it really paints; bundled looks use their actual background artwork. */
@Composable
private fun ThemeThumbnail(choice: ThemeChoice) {
    val shape = RoundedCornerShape(THUMBNAIL_CORNER)
    Box(
        modifier = Modifier
            .size(width = THUMBNAIL_WIDTH, height = THUMBNAIL_HEIGHT)
            .clip(shape)
            .border(width = THUMBNAIL_EDGE, color = MaterialTheme.colorScheme.outlineVariant, shape = shape),
    ) {
        when (choice) {
            is ThemeChoice.Pack -> AsyncImage(
                model = "file:///android_asset/${choice.pack.ground.asset}",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            is ThemeChoice.Plain -> Row(modifier = Modifier.fillMaxSize()) {
                choice.theme.thumbnailGrounds().forEach { ground ->
                    Box(modifier = Modifier.weight(WEIGHT_FILL).fillMaxSize().background(ground))
                }
            }
        }
    }
}

/** The ground a plain theme paints. System uses both halves because the device chooses between them. */
internal fun AppTheme.thumbnailGrounds(): List<Color> = when (this) {
    AppTheme.System -> listOf(ThumbnailLight, ThumbnailDark)
    AppTheme.Light -> listOf(ThumbnailLight)
    AppTheme.Dark -> listOf(ThumbnailDark)
    AppTheme.Amoled -> listOf(ThumbnailAmoled)
}

@Composable
private fun ThemeChoice.label(): String = when (this) {
    is ThemeChoice.Plain -> stringResource(theme.labelRes())
    is ThemeChoice.Pack -> pack.name
}

/** Accent and glass tint are visual choices: names stay in semantics while the eye gets a compact swatch grid. */
private fun LazyListScope.colourGroup(state: AppearanceUiState, actions: AppearanceActions) {
    item { SectionHeader(text = stringResource(R.string.settings_section_colour)) }
    item {
        SettingsGroup {
            AccentColorGridRow(state = state, actions = actions)

            val activeAccent = MaterialTheme.colorScheme.primary
            val storedAccentArgb = state.accent.argbFor(state.isDark)
            ColorGridRow(
                label = stringResource(R.string.settings_tint_colour),
                options = GlassTint.all(state.backgroundThemes),
                presentation = ColorGridPresentation(
                    currentLabel = state.glassTint.label(state.backgroundThemes),
                    currentColor = state.glassTint.resolvedColor(activeAccent, storedAccentArgb),
                ),
                labelOf = { tint -> tint.label(state.backgroundThemes) },
                argbOf = { tint ->
                    tint.resolvedColor(activeAccent, storedAccentArgb).toArgb().toUInt().toLong()
                },
                isSelected = { tint -> tint == state.glassTint },
                onSelected = actions.onGlassTintChanged,
            )

            DropdownRow(
                label = stringResource(R.string.settings_text_contrast),
                options = TextContrast.entries,
                selected = state.textContrast,
                labelOf = { contrast -> stringResource(contrast.labelRes()) },
                onSelected = actions.onTextContrastChanged,
            )
        }
    }
}

/**
 * Wallpaper colour is one accent choice, not a separate switch.
 *
 * The real Material You primary colour is shown at the bottom of the palette. Choosing it enables dynamic
 * colour; choosing any explicit swatch stores that swatch first and then disables dynamic colour in the
 * ViewModel, so the selected dot and the colour actually painted can never disagree.
 */
@Composable
private fun AccentColorGridRow(state: AppearanceUiState, actions: AppearanceActions) {
    val wallpaperColor = dynamicAccentColor(state.isDark)
    val wallpaperSelected = state.dynamicColor && wallpaperColor != null
    val storedAccentColor = Color(state.accent.argbFor(state.isDark))
    val currentColor = wallpaperColor?.takeIf { wallpaperSelected } ?: storedAccentColor
    val wallpaperLabel = if (wallpaperColor != null) stringResource(R.string.settings_dynamic_color) else null

    ColorGridRow(
        label = stringResource(R.string.settings_accent_colour),
        options = AccentScheme.all(state.backgroundThemes),
        presentation = ColorGridPresentation(
            currentLabel = if (wallpaperSelected) {
                wallpaperLabel ?: state.accent.label(state.backgroundThemes)
            } else {
                state.accent.label(state.backgroundThemes)
            },
            currentColor = currentColor,
            bottomOption = if (wallpaperLabel != null && wallpaperColor != null) {
                BottomColorOptionState(
                    label = wallpaperLabel,
                    color = wallpaperColor,
                    selected = wallpaperSelected,
                    onSelected = { actions.onDynamicColorChanged(true) },
                )
            } else {
                null
            },
        ),
        labelOf = { accent -> accent.label(state.backgroundThemes) },
        argbOf = { accent -> accent.argbFor(state.isDark) },
        isSelected = { accent -> !wallpaperSelected && accent == state.accent },
        onSelected = actions.onAccentChanged,
    )
}

/** Blur collapses to one summary row; the slider only occupies space while the reader is adjusting it. */
private fun LazyListScope.glassGroup(state: AppearanceUiState, actions: AppearanceActions) {
    item { SectionHeader(text = stringResource(R.string.settings_section_glass)) }
    item {
        SettingsGroup {
            val valueLabel = if (state.glassBlurDp <= 0) {
                stringResource(R.string.settings_blur_off)
            } else {
                stringResource(R.string.settings_blur_value, state.glassBlurDp)
            }
            ExpandableSliderRow(
                label = stringResource(R.string.settings_blur),
                valueLabel = valueLabel,
                value = state.glassBlurDp.toFloat(),
                range = 0f..GlassBlur.MAX_DP.toFloat(),
                steps = GlassBlur.MAX_DP - 1,
                onValueChange = { dp -> actions.onGlassBlurChanged(dp.roundToInt()) },
            )
            SwitchRow(
                label = stringResource(R.string.settings_card_tint),
                checked = state.cardGlassTintEnabled,
                onCheckedChange = actions.onCardGlassTintChanged,
            )
            SwitchRow(
                label = stringResource(R.string.settings_system_tint),
                checked = state.systemGlassTintEnabled,
                onCheckedChange = actions.onSystemGlassTintChanged,
            )
        }
    }
}

private fun LazyListScope.languageGroup(state: AppearanceUiState, actions: AppearanceActions) {
    item { SectionHeader(text = stringResource(R.string.settings_language)) }
    item {
        SettingsGroup {
            DropdownRow(
                label = stringResource(R.string.settings_language),
                showLabel = false,
                options = AppLanguage.entries,
                selected = state.language,
                labelOf = { language -> language.label() },
                onSelected = actions.onLanguageChanged,
            )
        }
    }
}

/** One group of related settings, on the same glass used by the rest of the app. */
@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) { content() }
    }
}

/** A text/picture choice that expands inside its card instead of opening a Popup window. */
@Composable
private fun <T> DropdownRow(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    leadingOf: (@Composable (T) -> Unit)? = null,
    showLabel: Boolean = true,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val current = labelOf(selected)
    val turn by animateFloatAsState(
        targetValue = if (expanded) CHEVRON_OPEN else 0f,
        label = "chevron",
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_HEIGHT)
                .clickable(role = Role.DropdownList) { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { contentDescription = "$label, $current" },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showLabel) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(WEIGHT_FILL),
                )
            }
            leadingOf?.invoke(selected)
            Text(
                text = current,
                style = if (showLabel) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                color = if (showLabel) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = (if (showLabel) Modifier else Modifier.weight(WEIGHT_FILL)).clearAndSetSemantics { },
            )
            Chevron(turn)
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                options.forEach { option ->
                    OptionRow(
                        label = labelOf(option),
                        isSelected = option == selected,
                        leading = leadingOf?.let { draw -> { draw(option) } },
                        onClick = {
                            expanded = false
                            onSelected(option)
                        },
                    )
                }
            }
        }
    }
}

/** The currently rendered colour plus the optional non-hue source shown below the grid. */
private class ColorGridPresentation(
    val currentLabel: String,
    val currentColor: Color,
    val bottomOption: BottomColorOptionState? = null,
)

/** A named colour source that follows the regular hue-sorted swatches. */
private class BottomColorOptionState(
    val label: String,
    val color: Color,
    val selected: Boolean,
    val onSelected: () -> Unit,
)

/**
 * A colour choice whose regular options are dots, followed by an optional full-width special colour row.
 *
 * The swatch names stay in semantics instead of being drawn under every dot. Wallpaper is deliberately the
 * exception: it is a different source rather than another named hue, so its bottom row names that source and
 * shows the exact Material You colour beside it.
 */
@Composable
private fun <T> ColorGridRow(
    label: String,
    options: List<T>,
    presentation: ColorGridPresentation,
    labelOf: @Composable (T) -> String,
    argbOf: (T) -> Long,
    isSelected: (T) -> Boolean,
    onSelected: (T) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(
        targetValue = if (expanded) CHEVRON_OPEN else 0f,
        label = "colour-chevron",
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_HEIGHT)
                .clickable(role = Role.DropdownList) { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { contentDescription = "$label, ${presentation.currentLabel}" },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(WEIGHT_FILL))
            Swatch(color = presentation.currentColor, selected = false, size = COLLAPSED_SWATCH_SIZE)
            Chevron(turn)
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    options.sortedByHue(argbOf).forEach { option ->
                        val optionLabel = labelOf(option)
                        ColorSwatchOption(
                            color = Color(argbOf(option)),
                            label = optionLabel,
                            selected = isSelected(option),
                            onClick = {
                                expanded = false
                                onSelected(option)
                            },
                        )
                    }
                }
                presentation.bottomOption?.let { bottomOption ->
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    BottomColorOption(
                        label = bottomOption.label,
                        color = bottomOption.color,
                        selected = bottomOption.selected,
                        onClick = {
                            expanded = false
                            bottomOption.onSelected()
                        },
                    )
                }
            }
        }
    }
}

/** One 48dp palette cell; its name is semantic-only and selection is shown as a stronger ring. */
@Composable
private fun ColorSwatchOption(color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(COLOR_CELL_SIZE)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Swatch(color = color, selected = selected, size = GRID_SWATCH_SIZE)
    }
}

/** A named colour source that sits under the hue-sorted swatch grid. */
@Composable
private fun BottomColorOption(label: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(WEIGHT_FILL))
        Swatch(color = color, selected = selected, size = GRID_SWATCH_SIZE)
    }
}

/** A blur summary that expands the actual slider inline. */
@Composable
private fun ExpandableSliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(
        targetValue = if (expanded) CHEVRON_OPEN else 0f,
        label = "slider-chevron",
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_HEIGHT)
                .clickable(role = Role.DropdownList) { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { contentDescription = "$label, $valueLabel" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$label - $valueLabel",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(WEIGHT_FILL).clearAndSetSemantics { },
            )
            Chevron(turn)
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    valueRange = range,
                    steps = steps,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .semantics { contentDescription = label },
                )
            }
        }
    }
}

@Composable
private fun Chevron(turn: Float) {
    Icon(
        imageVector = Icons.Filled.KeyboardArrowDown,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.rotate(turn),
    )
}

/** One named choice inside an opened text/picture row. */
@Composable
private fun OptionRow(label: String, isSelected: Boolean, leading: (@Composable () -> Unit)?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .padding(start = OPTION_INSET, end = 16.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(WEIGHT_FILL),
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(color = color, shape = CircleShape)
            .border(
                width = if (selected) SELECTED_SWATCH_EDGE_WIDTH else SWATCH_EDGE_WIDTH,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            ),
    )
}

/** Sort chromatic choices red→yellow→green→cyan→blue→purple; near-neutrals follow the rainbow. */
private fun <T> List<T>.sortedByHue(argbOf: (T) -> Long): List<T> = sortedWith(
    compareBy<T>(
        { option -> argbOf(option).isNearNeutral() },
        { option -> argbOf(option).hueDegrees() },
        { option -> argbOf(option) },
    ),
)

private fun Long.isNearNeutral(): Boolean {
    val r = ((this shr RED_SHIFT) and BYTE_MASK).toFloat() / BYTE_MAX
    val g = ((this shr GREEN_SHIFT) and BYTE_MASK).toFloat() / BYTE_MAX
    val b = (this and BYTE_MASK).toFloat() / BYTE_MAX
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    return max <= 0f || (max - min) / max < NEUTRAL_SATURATION
}

/** Enough HSV for deterministic presentation order; this is not used to alter any colour. */
private fun Long.hueDegrees(): Float {
    val r = ((this shr RED_SHIFT) and BYTE_MASK).toFloat() / BYTE_MAX
    val g = ((this shr GREEN_SHIFT) and BYTE_MASK).toFloat() / BYTE_MAX
    val b = (this and BYTE_MASK).toFloat() / BYTE_MAX
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    if (delta <= 0f) return HUE_CIRCLE
    val raw = when (max) {
        r -> ((g - b) / delta) % HUE_SECTORS
        g -> (b - r) / delta + GREEN_SECTOR
        else -> (r - g) / delta + BLUE_SECTOR
    }
    return (raw * HUE_SECTOR_DEGREES + HUE_CIRCLE) % HUE_CIRCLE
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

@Composable
private fun AccentScheme.label(themes: List<BackgroundTheme>): String = when {
    isFromTheme -> themes.firstOrNull(::belongsTo)?.name ?: stringResource(R.string.settings_accent_theme)
    else -> stringResource(AccentColor.ofKey(key).labelRes())
}

@Composable
private fun GlassTint.label(themes: List<BackgroundTheme>): String = when (this) {
    GlassTint.White -> stringResource(R.string.settings_tint_white)
    GlassTint.Warm -> stringResource(R.string.settings_tint_warm)
    GlassTint.Cool -> stringResource(R.string.settings_tint_cool)
    GlassTint.FollowAccent -> stringResource(R.string.settings_tint_accent)
    else -> {
        val accent = accentKey?.let { key -> AccentScheme.all(themes).firstOrNull { it.key == key } }
        accent?.label(themes) ?: stringResource(R.string.settings_accent_theme)
    }
}

private fun TextContrast.labelRes(): Int = when (this) {
    TextContrast.Automatic -> R.string.settings_contrast_auto
    TextContrast.High -> R.string.settings_contrast_high
    TextContrast.Soft -> R.string.settings_contrast_soft
}

@Composable
private fun AppLanguage.label(): String = displayName ?: stringResource(R.string.settings_language_system)

private val THUMBNAIL_WIDTH = 84.dp
private val THUMBNAIL_HEIGHT = 56.dp
private val THUMBNAIL_CORNER = 10.dp
private val THUMBNAIL_EDGE = 1.dp

private val ThumbnailLight = Color.White
private val ThumbnailDark = Color(0xFF1C1B1F)
private val ThumbnailAmoled = Color.Black

private val COLLAPSED_SWATCH_SIZE = 28.dp
private val GRID_SWATCH_SIZE = 32.dp
private val COLOR_CELL_SIZE = 48.dp
private val SWATCH_EDGE_WIDTH = 1.dp
private val SELECTED_SWATCH_EDGE_WIDTH = 3.dp
private val ROW_MIN_HEIGHT = 48.dp
private val OPTION_INSET = 32.dp

private const val CHEVRON_OPEN = 180f
private const val WEIGHT_FILL = 1f
private const val BYTE_MASK = 0xFFL
private const val BYTE_MAX = 255f
private const val NEUTRAL_SATURATION = 0.08f
private const val HUE_SECTORS = 6f
private const val HUE_SECTOR_DEGREES = 60f
private const val HUE_CIRCLE = 360f

/** Where each channel sits in a packed ARGB long, and which sector of the hue circle it centres on. */
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val GREEN_SECTOR = 2f
private const val BLUE_SECTOR = 4f
