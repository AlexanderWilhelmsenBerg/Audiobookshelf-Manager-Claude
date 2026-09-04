package com.example.shelfplayer.ui.gesture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/**
 * PRODUCT_SPEC SET-002 / 16.2 — dragging back past the first page leaves the screen.
 *
 * ### Why this cannot be an ordinary drag handler any more
 *
 * It used to be: a `pointerInput` on the settings `Column` that watched for a rightward drag and called
 * `onNavigateUp`. That worked while the tabs were switched by a threshold gesture, and it stops working
 * the moment the tabs become a `HorizontalPager`. Pointer events reach the innermost node first, the pager
 * is inside the `Column`, and a scrollable **consumes** the drag it wins — so the handler on the parent
 * would never fire again. Silently: nothing would look broken, the gesture would simply stop existing.
 *
 * Nested scroll is the mechanism for exactly this. A pager at its first page cannot scroll back any
 * further, so the backward part of the drag comes out the other side as *unconsumed* scroll, and this
 * connection is what is listening for it. It therefore triggers only where it should — at the first page,
 * dragging backwards — without having to ask the pager what page it is on.
 *
 * ### Why the fling, and not the drag
 *
 * [onPreFling] runs when the finger lifts, which is where the decision belongs: a reader who drags a
 * little and changes their mind drags back and finishes below the threshold, and nothing happens. Acting
 * during the drag would leave the screen out from under a gesture still in progress.
 *
 * @param onLeave what "back" means here — the settings screen returning to the shelf.
 */
@Composable
internal fun rememberSwipeToLeave(onLeave: () -> Unit): NestedScrollConnection {
    val threshold = with(LocalDensity.current) { LEAVE_THRESHOLD.toPx() }
    // "Back" is a drag to the *right* in a left-to-right layout and to the left in a right-to-left one,
    // because it is the direction the previous page would come from.
    val backwards = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    return remember(onLeave, threshold, backwards) {
        object : NestedScrollConnection {
            private var travelled = 0f

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Only what the pager could not use, and only from a finger — a programmatic
                // `animateScrollToPage` past the end must not read as somebody asking to leave.
                if (source == NestedScrollSource.UserInput) travelled += available.x * backwards
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val leaving = travelled >= threshold
                travelled = 0f
                if (leaving) onLeave()
                return Velocity.Zero
            }
        }
    }
}

/**
 * How far past the first page a drag must go before it leaves the screen.
 *
 * 56dp, the same distance the threshold gesture this replaces used, and for the same reason: well past
 * touch slop, so the sideways drift in a vertical flick cannot reach it, and short enough for a thumb on
 * a phone held one-handed. It is measured against *unconsumed* scroll, which only exists once the pager
 * has run out of pages, so the whole 56dp is deliberate over-drag.
 */
private val LEAVE_THRESHOLD: Dp = 56.dp
