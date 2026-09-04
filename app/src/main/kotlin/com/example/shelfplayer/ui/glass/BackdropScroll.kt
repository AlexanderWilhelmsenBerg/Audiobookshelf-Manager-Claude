package com.example.shelfplayer.ui.glass

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * PRODUCT_SPEC SET-002 (Appearance) — how far the foreground has scrolled, for the backdrop to lag behind.
 *
 * ### Why a shared value rather than reading a list's state
 *
 * The backdrop is drawn once, beneath the whole navigation graph, and the thing that scrolls is a list on
 * whichever screen happens to be showing. The backdrop cannot reach into that list, and it should not
 * have to know which screens have one.
 *
 * ### Why a scroll *connection* and not `firstVisibleItemScrollOffset`
 *
 * A `LazyColumn` reports its position as an item index plus an offset within that item, and turning that
 * into pixels needs every preceding item's height — which a lazy list does not know for items it has
 * never composed. Accumulating the consumed delta needs none of that, works for any scrollable, and is
 * exactly the quantity a parallax wants: how far the content has actually moved under the finger.
 *
 * ### Why it is clamped
 *
 * The artwork is finite. Past [MAX_TRAVEL] of backdrop movement there is no more picture to show, so the
 * accumulator stops rather than sliding the image off its own edge — a long shelf would otherwise scroll
 * the background clean out of view and leave the base colour behind it.
 */
@Stable
internal class BackdropScroll {
    /** Pixels the foreground has scrolled, floored at zero and capped at what the artwork can cover. */
    var scrolledPx by mutableFloatStateOf(0f)
        private set

    private var maxPx = 0f

    /** Called once the backdrop knows how much travel its artwork has. */
    fun allow(travelPx: Float) {
        maxPx = travelPx
        scrolledPx = scrolledPx.coerceIn(0f, travelPx)
    }

    fun onScrolled(deltaPx: Float) {
        if (maxPx <= 0f) return
        scrolledPx = (scrolledPx - deltaPx).coerceIn(0f, maxPx)
    }
}

internal val LocalBackdropScroll = staticCompositionLocalOf { BackdropScroll() }

/**
 * Reports a screen's vertical scrolling to the backdrop, so the artwork can lag behind it.
 *
 * `onPostScroll` rather than `onPreScroll`: what the backdrop should follow is what the content actually
 * moved, not what the finger asked for. At the end of a list those differ, and a backdrop that kept
 * sliding while the content had stopped would drift away from it.
 *
 * Both user drags and flings count — a fling is the same gesture still travelling, and a backdrop that
 * froze the moment the finger lifted would be the most conspicuous thing on the screen.
 */
@androidx.compose.runtime.Composable
internal fun Modifier.reportsBackdropScroll(): Modifier {
    val backdrop = LocalBackdropScroll.current
    val connection = androidx.compose.runtime.remember(backdrop) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                backdrop.onScrolled(consumed.y)
                return Offset.Zero
            }
        }
    }
    return nestedScroll(connection)
}

/**
 * How far the backdrop may move in total.
 *
 * The artwork is drawn this much taller than the window, and the parallax spends exactly that. Larger
 * would mean scaling the picture up further for travel nobody notices; smaller and the movement stops
 * within one flick of the shelf.
 */
internal val MAX_TRAVEL: Dp = 96.dp

/**
 * How much of the foreground's scroll the backdrop takes.
 *
 * Well under half, because the point is that the picture is *far away*. At higher fractions it reads as a
 * second scrolling surface rather than as distance, and the parallax stops being a depth cue and starts
 * being a distraction behind the text.
 */
internal const val PARALLAX_FRACTION = 0.28f
