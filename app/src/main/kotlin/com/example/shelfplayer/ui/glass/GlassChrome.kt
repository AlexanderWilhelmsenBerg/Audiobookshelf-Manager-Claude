package com.example.shelfplayer.ui.glass

import androidx.compose.foundation.background
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** The app-wide source used by floating chrome that should refract what is actually behind it. */
internal val LocalGlassHazeState = staticCompositionLocalOf<HazeState?> { null }

/** Keeps Home's floating navigation clear of the overlaid mini player without shrinking the blur source. */
internal val LocalPlayerChromeBottomInset = staticCompositionLocalOf { 0.dp }

/**
 * One frosted-glass recipe for the navigation capsule and mini player.
 *
 * The source-backed path is genuine backdrop blur. The fallback is intentionally only a translucent white
 * scrim so previews, Robolectric, and a missing provider never turn the chrome into an opaque Material surface.
 */
internal fun Modifier.frostedGlass(
    state: HazeState?,
    backgroundColor: Color,
    tintAlpha: Float,
    fallbackTintAlpha: Float,
    blurRadius: Dp,
    noiseFactor: Float = DEFAULT_GLASS_NOISE,
): Modifier = if (state == null) {
    background(Color.White.copy(alpha = fallbackTintAlpha))
} else {
    hazeEffect(
        state = state,
        style = HazeStyle(
            backgroundColor = backgroundColor,
            tint = HazeTint(Color.White.copy(alpha = tintAlpha)),
            blurRadius = blurRadius,
            noiseFactor = noiseFactor,
            fallbackTint = HazeTint(Color.White.copy(alpha = fallbackTintAlpha)),
        ),
    )
}

private const val DEFAULT_GLASS_NOISE = 0.07f
