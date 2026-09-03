package com.example.shelfplayer.ui.gesture

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * PRODUCT_SPEC 16.2 — a horizontal swipe that moves between the places a bar already offers.
 *
 * ### Why a raw pointer input is safe here, when it usually is not
 *
 * The obvious worry is that a screen-level drag handler steals from the shelf carousels, the filter chips
 * and the sort chips — every one of them a `LazyRow` inside the body this sits on. It does not, and the
 * reason is Compose's dispatch order rather than anything written here.
 *
 * Pointer events reach the innermost hit node first on the `Main` pass. A `LazyRow` under the finger waits
 * for its own touch slop and **consumes** the change when it wins, and [detectHorizontalDragGestures] hangs
 * off `awaitTouchSlopOrCancellation`, which cancels the moment it sees a consumed change. So a drag that
 * begins on a carousel scrolls the carousel and never reaches this; a drag that begins anywhere else
 * reaches this. The same applies to a vertical drag: the `LazyColumn` wins vertical slop first.
 *
 * That is a property of the framework, not a guarantee this file can make. If a future child both accepts
 * horizontal drags and declines to consume them, both will fire — which is why every caller keeps the bar
 * or the tab row that this duplicates, rather than replacing it.
 *
 * ### The threshold
 *
 * [SWIPE_THRESHOLD] rather than acting on touch slop alone. Slop is about ten device pixels, which is a
 * wobble in a vertical scroll, not an intention to change page. Distance is compared at the end of the drag
 * rather than during it, so a listener who changes their mind and drags back finishes where they started.
 *
 * ### Direction
 *
 * [onNext] is the place to the *right* of this one in reading order, so a drag towards the left reveals it,
 * the way a pager does. The sign flips under a right-to-left layout, where reading order does.
 *
 * @param onPrevious the place before this one, or `null` when there is none and the gesture should do
 *   nothing. A caller with somewhere else to go — the settings screen returning to the shelf from its first
 *   tab — passes that instead of `null`.
 * @param onNext the place after this one, or `null` at the end.
 */
@Composable
internal fun Modifier.swipeBetween(onPrevious: (() -> Unit)?, onNext: (() -> Unit)?): Modifier {
    val threshold = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return this.pointerInput(onPrevious, onNext, threshold, isRtl) {
        var travelled = 0f
        detectHorizontalDragGestures(
            onDragStart = { travelled = 0f },
            onDragCancel = { travelled = 0f },
            onDragEnd = {
                val forward = if (isRtl) travelled >= threshold else travelled <= -threshold
                val backward = if (isRtl) travelled <= -threshold else travelled >= threshold
                when {
                    forward -> onNext?.invoke()
                    backward -> onPrevious?.invoke()
                }
            },
            onHorizontalDrag = { change, amount ->
                travelled += amount
                // Consumed only once this gesture has been won: `detectHorizontalDragGestures` does not
                // call this until horizontal slop is passed, and by then no child wanted it.
                change.consume()
            },
        )
    }
}

/**
 * How far a drag must travel to count as a page change.
 *
 * 56dp is a little over a finger's width and well past touch slop, so it cannot be reached by the sideways
 * drift in a vertical flick, and it is short enough to perform with a thumb on a phone held one-handed.
 */
private val SWIPE_THRESHOLD: Dp = 56.dp

/**
 * The entry before [current] in [entries], or `null` at the start.
 *
 * Deliberately no wrapping. A bar with four tabs shows the reader where they are in a row, and a swipe that
 * jumped from the last to the first would contradict the thing it is meant to be driving.
 */
internal fun <T> previousOf(entries: List<T>, current: T): T? = entries.getOrNull(entries.indexOf(current) - 1)

/** The entry after [current] in [entries], or `null` at the end. See [previousOf]. */
internal fun <T> nextOf(entries: List<T>, current: T): T? =
    entries.indexOf(current).let { index -> if (index < 0) null else entries.getOrNull(index + 1) }
