package com.example.shelfplayer.ui.glass

import androidx.compose.foundation.background
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * The app-wide source used by floating chrome that should refract what is actually behind it.
 *
 * This one belongs to the **mini player**, which is a sibling of the navigation graph and so can blur the
 * whole of it. Chrome drawn *inside* the graph — Home's axis capsule, for one — cannot use it: an effect
 * that is a descendant of its own source is asking to blur itself. Such chrome owns a nearer state whose
 * source is only the content it floats over.
 */
internal val LocalGlassHazeState = staticCompositionLocalOf<HazeState?> { null }

/** Keeps Home's floating navigation clear of the overlaid mini player without shrinking the blur source. */
internal val LocalPlayerChromeBottomInset = staticCompositionLocalOf { 0.dp }

/**
 * One recipe, one set of numbers, for every frosted surface in the app.
 *
 * These were the mini player's values; the navigation capsule carried its own near-copies until they
 * drifted apart by 4dp of blur for no stated reason. A second frosted surface should read this object
 * rather than pick its own, so that "the app's glass" stays one decision.
 */
internal object GlassDefaults {
    /** How far the backdrop is smeared. Large enough that text behind the chrome stops being readable. */
    val BlurRadius: Dp = 28.dp

    /** The white wash over the blur, which is what makes it read as frosted rather than merely out of focus. */
    const val TINT_ALPHA: Float = 0.18f

    /**
     * The wash used where there is no blur at all.
     *
     * Heavier than [TINT_ALPHA] because it is doing the whole job alone — see [frostedGlass] for when that
     * happens, which is more devices than it looks.
     */
    const val FALLBACK_TINT_ALPHA: Float = 0.28f

    /** A little grain, so a large flat blur does not band on a gradient. */
    const val NOISE_FACTOR: Float = 0.07f
}

/**
 * One frosted-glass recipe for the navigation capsule, the mini player, and anything else that floats.
 *
 * ### What each device actually gets
 *
 * Verified against `haze-android-1.6.10`'s bytecode rather than assumed. `HazeState()`'s no-argument
 * constructor sets `blurEnabled = HazeDefaults.blurEnabled()`, which is `Build.VERSION.SDK_INT >= 31`, and
 * `updateBlurEffectIfNeeded` then picks:
 *
 *  - **API 31+**, hardware-accelerated canvas — `RenderEffectBlurEffect`, a real backdrop blur;
 *  - **API 26–30** — `blurEnabled` is false, so Haze's own RenderScript path is never even attempted and it
 *    falls straight to `ScrimBlurEffect`: a flat [FALLBACK_TINT_ALPHA] wash, no blur.
 *
 * So on Android 8.0 to 11 this chrome is a translucent white band, not glass. That is a deliberate accepted
 * cost — the alternative is an opaque bar on new phones too — but it is the reason [FALLBACK_TINT_ALPHA] is
 * heavier than [TINT_ALPHA]: the wash has to separate the chrome from the content on its own.
 *
 * ### The [shape] parameter is not decoration
 *
 * A capsule that is clipped by the caller but drawn by Haze is a capsule only while Haze draws. When the
 * effect declines — no state, a preview, Robolectric — the fallback must still be the same shape, or the
 * chrome changes silhouette between devices. Passing the shape here keeps the two paths honest.
 */
internal fun Modifier.frostedGlass(
    state: HazeState?,
    backgroundColor: Color,
    shape: Shape = RectangleShape,
    tintAlpha: Float = GlassDefaults.TINT_ALPHA,
    fallbackTintAlpha: Float = GlassDefaults.FALLBACK_TINT_ALPHA,
    blurRadius: Dp = GlassDefaults.BlurRadius,
    noiseFactor: Float = GlassDefaults.NOISE_FACTOR,
): Modifier = if (state == null) {
    background(color = Color.White.copy(alpha = fallbackTintAlpha), shape = shape)
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

/**
 * How much of the window the mini player is covering, for a screen that needs to scroll clear of it.
 *
 * ### Why every scrollable screen has to ask
 *
 * The mini player used to sit in a `Column` beneath the navigation graph, so the graph was simply shorter
 * while a book was loaded and no screen had to know the player existed. It is an overlay now — that is what
 * gives it something to frost — and an overlay covers the bottom of whatever is under it. Without this,
 * the last row of a settings list, a series, or a download queue sits behind the player with no way to
 * scroll it into view.
 *
 * Add it to a list's **`contentPadding`**, never to its layout padding: content padding lengthens the
 * scroll range and leaves every item where it is, so a player appearing mid-scroll does not shove the
 * screen upwards under the reader's thumb.
 *
 * The system navigation bar is deliberately **not** included. A screen with a `Scaffold` already receives
 * that in its own inner padding, and adding it twice is a gap the size of the gesture handle.
 */
@androidx.compose.runtime.Composable
internal fun playerChromeClearance(): Dp = LocalPlayerChromeBottomInset.current.coerceAtLeast(0.dp)
