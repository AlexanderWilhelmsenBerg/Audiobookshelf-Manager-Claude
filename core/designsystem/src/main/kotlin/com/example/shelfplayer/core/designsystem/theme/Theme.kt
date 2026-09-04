package com.example.shelfplayer.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

/**
 * PRODUCT_SPEC SET-002 (Appearance/accessibility) — system/light/dark plus optional dynamic color.
 *
 * The palette is deliberately restrained: audiobook covers are the colourful element on every
 * screen, and a saturated surface competes with them. Contrast pairs come from Material 3's tonal
 * roles rather than hand-picked hex values, which is what keeps the high-contrast and large-text
 * requirements in PRODUCT_SPEC 2.10 achievable without a second palette.
 */
private val ShelfLightColors = lightColorScheme(
    primary = Color(0xFF2F5D62),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB6E5E8),
    onPrimaryContainer = Color(0xFF00201F),
    secondary = Color(0xFF6E5B3F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF8DFBA),
    onSecondaryContainer = Color(0xFF261904),
    surface = Color(0xFFFBFAF8),
    onSurface = Color(0xFF1A1C1C),
    surfaceVariant = Color(0xFFDBE4E4),
    onSurfaceVariant = Color(0xFF3F4949),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

private val ShelfDarkColors = darkColorScheme(
    primary = Color(0xFF9ACACC),
    onPrimary = Color(0xFF003735),
    primaryContainer = Color(0xFF14494C),
    onPrimaryContainer = Color(0xFFB6E5E8),
    secondary = Color(0xFFDBC3A0),
    onSecondary = Color(0xFF3D2E16),
    secondaryContainer = Color(0xFF55442A),
    onSecondaryContainer = Color(0xFFF8DFBA),
    surface = Color(0xFF101414),
    onSurface = Color(0xFFE0E3E2),
    surfaceVariant = Color(0xFF3F4949),
    onSurfaceVariant = Color(0xFFBEC9C8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/**
 * @param darkTheme resolved by the caller from the user's stored preference, so that "follow the
 *   system" is one branch of a decision made in one place instead of being re-derived per screen.
 * @param dynamicColor honoured only where the platform supports it (API 31+). PRODUCT_SPEC SET-002
 *   makes this opt-in, so the default is the ShelfPlayer palette.
 * @param pureBlack collapses every surface role to true black — the AMOLED theme. Applied *after*
 *   [accent] so that an accent stays visible against it.
 * @param accent replaces the scheme's primary family with the reader's chosen colour, or `null` to keep
 *   the shipped one. A `Color` rather than a domain enum on purpose: this module knows how to draw a
 *   palette and deliberately does not know what a stored preference is, so `core:model` stays out of its
 *   dependencies and the theme can be previewed with any colour at all.
 */
@Composable
fun ShelfPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    pureBlack: Boolean = false,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current
    val base = when {
        dynamicColor && supportsDynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && supportsDynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> ShelfDarkColors
        else -> ShelfLightColors
    }
    // Dynamic colour is the device's own accent, so a chosen one would be overriding the thing the
    // reader turned on to see. Whichever they enabled last is not knowable here; Material You wins,
    // because it is the more specific request — "use my wallpaper" rather than "use this hue".
    val accented = if (accent != null && !(dynamicColor && supportsDynamicColor)) base.withAccent(accent) else base

    MaterialTheme(
        colorScheme = if (pureBlack) accented.asPureBlack() else accented,
        typography = ShelfPlayerTypography,
        content = content,
    )
}

/**
 * The chosen accent, put into the four primary roles that carry it.
 *
 * `onPrimary` is picked by **luminance** rather than by whether the theme is dark. Those are not the same
 * question: a light accent on a dark theme needs dark text on it, and the accent's own brightness is what
 * decides that. Choosing by theme instead is how a pale accent ends up with white text on it.
 *
 * The container is the accent blended most of the way into the surface, so it stays the same hue family
 * rather than reverting to the shipped teal — which is what an accent that only replaced `primary` looked
 * like, and it read as a bug.
 */
private fun ColorScheme.withAccent(accent: Color): ColorScheme = copy(
    primary = accent,
    onPrimary = contrastOn(accent),
    primaryContainer = lerp(accent, surface, CONTAINER_BLEND),
    onPrimaryContainer = contrastOn(lerp(accent, surface, CONTAINER_BLEND)),
)

/**
 * PRODUCT_SPEC SET-002 — the AMOLED theme: every large area is an unlit pixel.
 *
 * Only the *grounds* go black. `onSurface`, the accent and the error roles are left exactly as the dark
 * scheme had them, because this theme changes what the screen emits and not what a reader can tell apart.
 * The container roles step up in near-black rather than all collapsing to `#000000`, or a dialog and the
 * page behind it would have no edge between them.
 */
private fun ColorScheme.asPureBlack(): ColorScheme = copy(
    background = Color.Black,
    onBackground = onSurface,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = AmoledBright,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = AmoledContainerLow,
    surfaceContainer = AmoledContainer,
    surfaceContainerHigh = AmoledContainerHigh,
    surfaceContainerHighest = AmoledContainerHighest,
)

/**
 * The near-blacks the AMOLED theme steps up through.
 *
 * Not all `#000000`: a sheet, a menu and the page behind them are three surfaces, and if every one of
 * them were the same black there would be no edge between them at all. These are the smallest steps that
 * still read as separate, which keeps almost every pixel of a full-screen surface unlit.
 */
private val AmoledBright = Color(0xFF1A1A1A)
private val AmoledContainerLow = Color(0xFF080808)
private val AmoledContainer = Color(0xFF0D0D0D)
private val AmoledContainerHigh = Color(0xFF141414)
private val AmoledContainerHighest = Color(0xFF1C1C1C)

/** Not pure black: a hair of warmth stops dark-on-accent text reading as a hole punched in the colour. */
private val OnLightAccent = Color(0xFF10100F)

/** Black or white, whichever the eye can read on [background]. */
private fun contrastOn(background: Color): Color =
    if (background.luminance() > LUMINANCE_MIDPOINT) OnLightAccent else Color.White

/**
 * How far a container sits from its accent, towards the surface.
 *
 * High, because a container is a *background* for text: at anything less the accent's own saturation
 * fights the label on top of it, which is the failure the tonal palettes exist to avoid.
 */
private const val CONTAINER_BLEND = 0.78f

/**
 * Where "light enough to need dark text on it" begins.
 *
 * 0.5 rather than the WCAG crossover (about 0.18) deliberately: the accents here are mid-tones, and the
 * perceptual midpoint puts white on the darker half of them, which is the pairing with more headroom when
 * the surrounding surface is also light.
 */
private const val LUMINANCE_MIDPOINT = 0.5f
