package com.example.shelfplayer.ui.gesture

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.sign
import kotlin.math.tanh

/**
 * PRODUCT_SPEC 16.2 — a little give at the first and last page, with resistance and a spring back.
 *
 * ### What a pager does at its ends, and why it is not enough
 *
 * `HorizontalPager` clamps. Drag left on the last page and nothing moves at all — the gesture simply stops
 * answering, which reads as the app having missed the touch rather than as the end of the row. Asked for
 * from a device: *"when on the edge, either genres or books, allow for a little overspill. But add
 * resistance and snap back."*
 *
 * The platform's own stretch overscroll is not that either: it distorts the pixels rather than moving the
 * page, and below API 31 it is a glow. This moves the page, on every version, and comes back.
 *
 * ### How the delta gets here
 *
 * A scrollable that cannot scroll any further leaves its delta **unconsumed**, and unconsumed delta is
 * exactly what a nested-scroll parent's `onPostScroll` receives. So this fires only at the ends, without
 * having to ask the pager which page it is on.
 *
 * [onPreScroll] is the other half and is easy to miss: while the page is held out past its edge, a drag
 * back towards the middle must undo the overspill *before* the pager starts turning pages, or the two move
 * at once and the page appears to jump.
 *
 * ### Leaving the screen is the same gesture
 *
 * Settings wants a pull back from its first tab to leave for the shelf, and that pull is this overspill.
 * Folding it in rather than adding a second nested-scroll connection is deliberate: two connections reading
 * the same `available.x` would each count it, and the screen would leave at half the distance it looks
 * like. [onPulledPastStart] is called on release when the pull at the **start** edge passed
 * [LEAVE_THRESHOLD].
 */
@Composable
internal fun rememberEdgeOverspill(onPulledPastStart: (() -> Unit)? = null): EdgeOverspill {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return remember(density, scope, isRtl, onPulledPastStart) {
        EdgeOverspill(
            scope = scope,
            limitPx = with(density) { MAX_OVERSPILL.toPx() },
            leavePx = with(density) { LEAVE_THRESHOLD.toPx() },
            // "Back" is a drag to the right in a left-to-right layout, because it is the direction the
            // previous page would arrive from.
            backwards = if (isRtl) -1f else 1f,
            onPulledPastStart = onPulledPastStart,
        )
    }
}

/** The state and the nested-scroll connection behind [rememberEdgeOverspill]. */
internal class EdgeOverspill(
    private val scope: CoroutineScope,
    private val limitPx: Float,
    private val leavePx: Float,
    private val backwards: Float,
    private val onPulledPastStart: (() -> Unit)?,
) {
    /** How far the pull has travelled, before resistance. The offset is [resisted] applied to this. */
    private var pulled = 0f

    private val animated = Animatable(0f)

    /** How far to move the pager, in pixels. Read from a `Modifier.offset`, so it recomposes nothing. */
    val offsetPx: Float get() = animated.value

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || pulled == 0f) return Offset.Zero
            // Only a drag heading *back* towards the middle, and only as much of it as is outstanding.
            if (sign(available.x) == sign(pulled)) return Offset.Zero
            val absorbed = if (available.x.absoluteValue > pulled.absoluteValue) -pulled else available.x
            pulled += absorbed
            settle()
            return Offset(absorbed, 0f)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // Programmatic scrolling — `animateScrollToPage` from a tab tap — must not stretch anything.
            if (source != NestedScrollSource.UserInput || available.x == 0f) return Offset.Zero
            pulled += available.x
            settle()
            return Offset(available.x, 0f)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            val leaving = onPulledPastStart != null && pulled * backwards >= leavePx
            pulled = 0f
            // Before the spring, not after it. Leaving is what the reader asked for by letting go, and
            // making them watch a page settle first would delay it by the length of an animation they are
            // no longer looking at.
            if (leaving) onPulledPastStart.invoke()
            animated.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
            // The fling itself is left alone: at an edge there is nothing for it to fling, and swallowing
            // it would also swallow the fling that carries a page the rest of the way over.
            return Velocity.Zero
        }
    }

    private fun settle() {
        scope.launch { animated.snapTo(resisted(pulled, limitPx)) }
    }
}

/**
 * How far the page actually moves for a pull of [pulled] pixels.
 *
 * `tanh` because it is the shape the ask describes: it starts at one-to-one, so the first millimetre
 * follows the finger exactly and the page feels attached, and it flattens towards [limit], so pulling
 * harder gives progressively less and the edge feels like an edge.
 *
 * It approaches [limit] and, in `Float`, saturates at it once the pull is a couple of dozen times the
 * limit — which is the property that matters either way: the page cannot be dragged further than that,
 * and there is no point at which it stops responding, it just responds less.
 *
 * Odd about zero, so the two edges behave identically without a sign test.
 */
internal fun resisted(pulled: Float, limit: Float): Float = if (limit <= 0f) 0f else limit * tanh(pulled / limit)

/** How far the page may be pulled past its edge, however hard it is pulled. */
private val MAX_OVERSPILL: Dp = 72.dp

/**
 * How far a pull back from the first page must reach before releasing leaves the screen.
 *
 * Measured on the **pull**, not on the resisted offset, so it is a distance the finger travels rather than
 * one the page does — which is the one the reader is in control of. 56dp, the same figure the threshold
 * gesture this replaces used.
 */
private val LEAVE_THRESHOLD: Dp = 56.dp
