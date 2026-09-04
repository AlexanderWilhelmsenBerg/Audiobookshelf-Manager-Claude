package com.example.shelfplayer.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PRODUCT_SPEC SET-002 / 16.2 — where the tab underline is drawn while a drag is in progress.
 *
 * The owner's ask was *"on the navigation bar the selection should move to the next button when pulling
 * over"*. `TabRow` positions its own indicator from an `Int`, so it can only sit still or snap; this is the
 * arithmetic that puts it between two tabs instead, and the two edges that arithmetic can get wrong.
 */
class TabBlendTest {

    /** Sitting on a tab is that tab, with nothing to interpolate towards it. */
    @Test
    fun `a whole position rests on one tab`() {
        assertEquals(TabBlend(from = 1, to = 1, fraction = 0f), tabBlend(count = 4, position = 1f))
    }

    /** Mid-drag: between the second and the third, and 40% of the way. */
    @Test
    fun `a fractional position blends the two tabs it lies between`() {
        val blend = tabBlend(count = 4, position = 1.4f)

        assertEquals(1, blend.from)
        assertEquals(2, blend.to)
        assertEquals(0.4f, blend.fraction, absoluteTolerance = 1e-4f)
    }

    /**
     * **At the last tab, `to` must not run off the end.**
     *
     * `ceil(3.0)` is 3, not 4, and the difference is an `IndexOutOfBoundsException` on the tab a reader
     * lands on most often after swiping all the way across.
     */
    @Test
    fun `the last tab does not index past the end`() {
        assertEquals(TabBlend(from = 3, to = 3, fraction = 0f), tabBlend(count = 4, position = 3f))
    }

    /**
     * **An over-drag at either end clamps rather than wrapping.**
     *
     * Reachable: `currentPageOffsetFraction` is signed, so dragging back at the first tab makes the
     * position negative, and dragging on past the last takes it above the count. Wrapping would send the
     * underline across the whole bar in the wrong direction.
     */
    @Test
    fun `positions outside the range clamp to the ends`() {
        assertEquals(TabBlend(from = 0, to = 0, fraction = 0f), tabBlend(count = 4, position = -0.6f))
        assertEquals(TabBlend(from = 3, to = 3, fraction = 0f), tabBlend(count = 4, position = 7f))
    }

    /** No tabs is not a crash. `TabRow` renders an empty row before its first composition settles. */
    @Test
    fun `an empty row is answered rather than thrown at`() {
        assertEquals(TabBlend(from = 0, to = 0, fraction = 0f), tabBlend(count = 0, position = 2f))
    }
}
