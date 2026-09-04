package com.example.shelfplayer.core.model.settings

/**
 * PRODUCT_SPEC SET-002 (Appearance) — one bundled theme: a palette and the artwork it was drawn for.
 *
 * ### Why a theme carries a picture and not only colours
 *
 * Because the colours were chosen *against* the picture. The packs' own README says the palettes "assume
 * readable UI content is placed on cards, glass panels, or scrimmed overlays rather than directly on the
 * brightest areas of the background" — so a palette applied without its background is a palette whose
 * contrast was reasoned about on a ground that is no longer there. The two travel together or not at all.
 *
 * ### Why every bundled theme is dark
 *
 * The packs arrive as *Light* and *Background*, and it would be reasonable to read those as light and dark
 * modes. They are not. Measured across all six, `text.primary` sits at luminance 0.98–0.99 and
 * `background.base` at 0.04–0.16, and every one declares `lightIcons: true`. The "Light Pack" is three
 * *lighter, more colourful backgrounds* — its own README's words — carrying the same light-text-on-dark
 * treatment. [isDark] is therefore read from the palette rather than from which pack a theme arrived in,
 * so a genuinely light pack added later gets the right answer without anybody remembering to say so.
 *
 * ### Why the roles are grouped
 *
 * They mirror the JSON's own sections, which is the shape somebody comparing the two will expect — and
 * flat, this is twenty-two colours in one constructor, well past the point where an argument list is safe
 * to get right.
 */
data class BackgroundTheme(
    val id: String,
    val name: String,
    /** Which pack it came from, so the picker can group what was authored together. */
    val pack: String,
    val isDark: Boolean,
    val ground: ThemeGround,
    val surfaces: ThemeSurfaces,
    val glass: ThemeGlass,
    val accents: ThemeAccents,
    val text: ThemeText,
)

/**
 * The artwork and the two colours that stand in for it.
 *
 * @property asset the picture's path within the app's assets, ready for a loader.
 * @property base what to paint *before* the artwork has decoded, and behind it forever after. The packs
 *   supply this exactly so a background arriving a frame late arrives over its own colour, not over white.
 * @property scrim laid over the artwork so that text has a ground rather than a photograph. It is the
 *   reason the palettes can promise contrast at all — see the note on [BackgroundTheme].
 */
data class ThemeGround(val asset: String, val base: Long, val scrim: Long)

data class ThemeSurfaces(val card: Long, val cardElevated: Long, val navigation: Long, val divider: Long)

/** @property blurDp the pack's own suggestion, which the reader's blur slider may override. */
data class ThemeGlass(val tint: Long, val border: Long, val blurDp: Int)

data class ThemeAccents(
    val primary: Long,
    val primaryContainer: Long,
    val onPrimary: Long,
    val secondary: Long,
    val tertiary: Long,
    val error: Long,
)

data class ThemeText(val primary: Long, val secondary: Long, val muted: Long, val inverse: Long)
