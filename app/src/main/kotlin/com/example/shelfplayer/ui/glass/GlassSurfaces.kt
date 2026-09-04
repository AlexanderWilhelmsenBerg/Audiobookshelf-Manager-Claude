package com.example.shelfplayer.ui.glass

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.shelfplayer.core.model.settings.BackgroundTheme
import dev.chrisbanes.haze.HazeState
import kotlin.math.roundToInt

/**
 * PRODUCT_SPEC SET-002 — one card, frosted against the app's backdrop.
 *
 * ### Why a card needs an outline and the chrome does not
 *
 * Chrome floats over content, so its wash always has something to separate it from. A card sits *on* the
 * backdrop, and when the reader turns the card wash off — which is the whole point of the switch on a
 * black theme — a card with no wash and a blurred backdrop behind it has no edge at all. The hairline is
 * what keeps it a card. It is drawn from `outlineVariant` so it is a theme colour rather than a fourth
 * opinion about white.
 *
 * ### What this looks like where there is no blur
 *
 * The same thing every other frosted surface does below API 31: a flat wash at
 * [GlassDefaults.FALLBACK_TINT_ALPHA]. With the wash switched off *and* no blur there is nothing left but
 * the outline, so this deliberately keeps the fallback wash in that case — an invisible card is worse
 * than one that ignores the preference on a device that could never show the effect anyway.
 */
@Composable
internal fun Modifier.cardGlass(shape: Shape = RoundedCornerShape(GlassDefaults.CardCornerRadius)): Modifier {
    val preferences = LocalGlassPreferences.current
    val hazeState = LocalCardHazeState.current
    val hasBlur = hazeState != null
    val tinted = preferences.cardTintEnabled || !hasBlur
    return this
        .clip(shape)
        .frostedGlass(
            state = hazeState,
            backgroundColor = MaterialTheme.colorScheme.surface,
            shape = shape,
            tintAlpha = if (tinted) GlassDefaults.CARD_TINT_ALPHA else 0f,
            fallbackTintAlpha = GlassDefaults.FALLBACK_TINT_ALPHA,
            blurRadius = preferences.blurRadius,
            tintColor = preferences.tint,
        )
        .border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = GlassDefaults.CARD_OUTLINE_ALPHA),
            shape = shape,
        )
}

/**
 * PRODUCT_SPEC SET-002 — a `Card` that is glass rather than a filled surface.
 *
 * ### Why the container has to be transparent
 *
 * For exactly the reason `TopAppBar` did: `Card` paints its container colour over whatever the modifier
 * drew, so a card with the glass modifier and a default container is a card with an opaque surface on top
 * of an invisible blur. The elevations go to zero for the same reason — Material 3 expresses elevation as
 * a *tonal overlay* on the container, which is another opaque layer, and a shadow under a translucent
 * card reads as a mistake rather than as depth.
 *
 * One composable rather than the same four lines on each of the shelf's card types, because the failure
 * mode of a copy is silent: it looks right until the one that was missed is next to the ones that were
 * not.
 */
@Composable
internal fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(GlassDefaults.CardCornerRadius),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
    val glass = modifier.cardGlass(shape)
    if (onClick == null) {
        Card(modifier = glass, shape = shape, colors = colors, elevation = elevation, content = content)
    } else {
        Card(
            onClick = onClick,
            modifier = glass,
            shape = shape,
            colors = colors,
            elevation = elevation,
            content = content,
        )
    }
}

/**
 * PRODUCT_SPEC SET-002 — the app's chrome, frosted, honouring the reader's wash switch.
 *
 * The counterpart to [cardGlass] for the navigation capsule, the top bars and the mini player. Same
 * reasoning about the wash-off-and-no-blur case: chrome that vanished into the content it floats over
 * would be worse than chrome that keeps a wash on a device with no blur to replace it.
 */
@Composable
internal fun Modifier.systemGlass(state: HazeState?, backgroundColor: Color, shape: Shape): Modifier {
    val preferences = LocalGlassPreferences.current
    val tinted = preferences.systemTintEnabled || state == null
    return frostedGlass(
        state = state,
        backgroundColor = backgroundColor,
        shape = shape,
        tintAlpha = if (tinted) GlassDefaults.TINT_ALPHA else 0f,
        blurRadius = preferences.blurRadius,
        tintColor = preferences.tint,
    )
}

/**
 * PRODUCT_SPEC 2.10 — the content colour a screen **must** pass alongside a transparent container.
 *
 * ### Why this exists rather than being left to default
 *
 * `Scaffold`'s `contentColor` defaults to `contentColorFor(containerColor)`, which looks the container up
 * among the scheme's colour *pairs* and falls back to `LocalContentColor.current` when it finds no match.
 * `Color.Transparent` is in no pair, and `LocalContentColor` is `compositionLocalOf { Color.Black }` —
 * `MaterialTheme` does not provide it, only `Surface` does, and this app's root is a `Box`. So a
 * transparent container makes **every word on the screen black**, on every theme, and black on black on
 * AMOLED.
 *
 * Reported from a device: *"Now I can't read since the text is black, and on the black theme is black on
 * black."* It is invisible from the code — the container is transparent for a good reason, the change
 * reads as being about a background, and nothing in it mentions text. `GlassContentColorScreenTest` pins
 * both halves: that this is `onSurface`, and that the default really is black.
 */
@Composable
internal fun glassContentColor(): Color = MaterialTheme.colorScheme.onSurface

/**
 * PRODUCT_SPEC SET-002 — the ground the whole app is drawn on, and the thing its glass refracts.
 *
 * ### Why this exists at all
 *
 * Because frosted glass over a flat colour is a tinted rectangle. `GlassChrome`'s own KDoc says so about
 * the chrome, and it is more true of cards, which tile the screen. Making them glass without giving them
 * something to refract would have been a change that *appears to have done nothing*.
 *
 * ### The three grounds, in order of preference
 *
 * - **A bundled theme's artwork**, when one is chosen. Drawn over the pack's own `base` colour so a frame
 *   that arrives before the picture has decoded arrives in the right colour rather than in white, and
 *   under the pack's own `scrim` so text has a ground rather than a photograph.
 * - **A gradient off the accent**, otherwise. Two slow stops, enough that a card picks up a different
 *   shade at the top of the screen than at the bottom, which is what reads as glass.
 * - **Flat**, on AMOLED. That theme exists so a black pixel is an unlit pixel, and a gradient lights every
 *   one of them.
 *
 * ### The parallax
 *
 * The artwork is drawn [MAX_TRAVEL] taller than the window and slides up by a fraction of whatever the
 * foreground scrolled — see [BackdropScroll] for why that quantity comes from a nested-scroll connection
 * rather than from a list's own state. Because it is drawn taller, there is always picture under the
 * viewport: the movement never exposes the edge it came from.
 */
@Composable
internal fun Modifier.appBackdrop(flat: Boolean, theme: BackgroundTheme? = null): Modifier {
    val scheme = MaterialTheme.colorScheme
    val base = if (theme != null) Color(theme.ground.base) else scheme.surface
    val brush = when {
        theme != null -> null
        flat -> Brush.verticalGradient(listOf(base, base))
        else -> Brush.verticalGradient(
            listOf(
                blend(scheme.primary, base, TOP_ACCENT_WEIGHT),
                base,
                blend(scheme.primary, base, BOTTOM_ACCENT_WEIGHT),
            ),
        )
    }
    return this
        .fillMaxSize()
        .drawBehind { drawRect(color = base) }
        .then(if (brush == null) Modifier else Modifier.drawBehind { drawRect(brush = brush) })
}

/**
 * PRODUCT_SPEC SET-002 — a bundled theme's artwork, behind everything, moving slower than the content.
 *
 * Separate from [appBackdrop] because it draws a *composable* — an image has to be loaded, and a modifier
 * cannot do that. The colour half stays a modifier so that a screen with no theme costs no extra node.
 */
@Composable
internal fun BackdropArtwork(theme: BackgroundTheme, modifier: Modifier = Modifier) {
    val backdrop = LocalBackdropScroll.current
    val density = LocalDensity.current
    val travelPx = with(density) { MAX_TRAVEL.toPx() }
    LaunchedEffect(travelPx) { backdrop.allow(travelPx) }
    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        AsyncImage(
            model = "file:///android_asset/${theme.ground.asset}",
            // Decorative: the artwork carries no information, and naming it would put "Aurora Teal" in
            // front of every screen reader user on every screen.
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                /*
                 * Taller than the window by exactly the travel the parallax spends.
                 *
                 * Without this the picture is the height of the window, and sliding it up by any amount
                 * exposes its own bottom edge — a band of base colour that grows as you scroll, which is
                 * the most conspicuous way a parallax can fail.
                 */
                .height(maxHeight + MAX_TRAVEL)
                .offsetForParallax { -backdrop.scrolledPx * PARALLAX_FRACTION },
        )
        // The pack's own scrim, over the picture and under everything else. Without it the palettes'
        // contrast promises are made against a photograph rather than against a ground.
        Box(modifier = Modifier.fillMaxSize().drawBehind { drawRect(color = Color(theme.ground.scrim)) })
    }
}

/**
 * Moves a layer by a value read at **draw** time.
 *
 * `Modifier.offset { }` reads its lambda during layout, which is what we want here: the parallax changes
 * on every scroll frame, and recomposing the artwork — an `AsyncImage` — that often would be absurd. The
 * lambda form keeps the movement to a layout pass and never re-reads the image.
 */
private fun Modifier.offsetForParallax(offsetY: () -> Float): Modifier =
    offset { IntOffset(x = 0, y = offsetY().roundToInt()) }

/** [weight] of [of] over [onto], without pulling in a graphics `lerp` for two multiplications. */
private fun blend(of: Color, onto: Color, weight: Float): Color = Color(
    red = of.red * weight + onto.red * (1f - weight),
    green = of.green * weight + onto.green * (1f - weight),
    blue = of.blue * weight + onto.blue * (1f - weight),
    alpha = 1f,
    colorSpace = onto.colorSpace,
)

/**
 * How much accent the backdrop carries at each end.
 *
 * Small, and smaller at the bottom. The gradient's job is to give the glass something to refract, not to
 * be noticed: at more than this the shelf starts to look like a poster and the covers — which are the
 * colourful thing on the screen and are supposed to be — have to compete with their own background.
 */
private const val TOP_ACCENT_WEIGHT = 0.14f
private const val BOTTOM_ACCENT_WEIGHT = 0.06f
