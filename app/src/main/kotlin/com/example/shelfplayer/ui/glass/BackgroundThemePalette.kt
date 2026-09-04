package com.example.shelfplayer.ui.glass

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.example.shelfplayer.core.model.settings.BackgroundTheme

/**
 * PRODUCT_SPEC SET-002 (Appearance) — a bundled pack's roles, as a Material colour scheme.
 *
 * ### Why the surfaces keep their alpha and the text does not
 *
 * The packs give their card and navigation colours **with alpha** — `#CC133A49` — because they are meant
 * to sit over the artwork and let it through. Material's surface roles are used the same way here, so the
 * alpha is preserved and the background shows through every card without any extra work.
 *
 * Text is the opposite: `onSurface` at less than full alpha is text you can see the sky through, which is
 * the readability failure R-99 was about. Every `on*` role is forced opaque.
 *
 * ### Why `surface` is the card colour and not the ground
 *
 * Because the ground is the picture. Material paints `surface` behind ordinary content, and if that were
 * the pack's opaque `base` the artwork would be hidden by the very thing meant to sit on it. The scheme's
 * surface is therefore the pack's translucent card, and the picture is drawn beneath everything by
 * `appBackdrop`.
 */
internal fun BackgroundTheme.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Color(accents.primary),
        onPrimary = Color(accents.onPrimary).opaque(),
        primaryContainer = Color(accents.primaryContainer),
        onPrimaryContainer = Color(text.primary).opaque(),
        secondary = Color(accents.secondary),
        onSecondary = Color(text.inverse).opaque(),
        secondaryContainer = Color(accents.primaryContainer),
        onSecondaryContainer = Color(text.primary).opaque(),
        tertiary = Color(accents.tertiary),
        onTertiary = Color(text.inverse).opaque(),
        // Translucent on purpose — this is what lets the artwork through the cards. See the KDoc.
        background = Color(ground.base),
        onBackground = Color(text.primary).opaque(),
        surface = Color(surfaces.card),
        onSurface = Color(text.primary).opaque(),
        surfaceVariant = Color(surfaces.navigation),
        onSurfaceVariant = Color(text.secondary).opaque(),
        surfaceContainerLowest = Color(ground.base),
        surfaceContainerLow = Color(surfaces.card),
        surfaceContainer = Color(surfaces.card),
        surfaceContainerHigh = Color(surfaces.cardElevated),
        surfaceContainerHighest = Color(surfaces.cardElevated),
        outline = Color(surfaces.divider).opaque(),
        outlineVariant = Color(surfaces.divider),
        error = Color(accents.error),
        onError = Color(text.inverse).opaque(),
    )
}

/**
 * The same colour, fully opaque.
 *
 * Applied to every `on*` role. A pack may well write its text colour with alpha — several write their
 * dividers and scrims that way — and text at less than full alpha over moving artwork is text that fades
 * in and out as the background scrolls past behind it.
 */
private fun Color.opaque(): Color = copy(alpha = 1f)
