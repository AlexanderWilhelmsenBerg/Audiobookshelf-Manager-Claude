package com.example.shelfplayer.ui.gesture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC 16.2 — how far the page moves when it is pulled past its first or last page.
 *
 * The ask was *"allow for a little overspill. But add resistance and snap back"*, and resistance is the
 * part with a shape to get right. These pin the three properties that shape has to have, each of which is
 * a way the gesture would feel wrong if it were missing.
 */
class EdgeOverspillTest {

    /** Nothing pulled, nothing moved. The page sits exactly at its edge until it is dragged. */
    @Test
    fun `no pull moves nothing`() {
        assertEquals(0f, resisted(pulled = 0f, limit = 200f))
    }

    /**
     * **The first millimetre follows the finger.**
     *
     * Resistance that begins at the first pixel reads as the page being reluctant rather than attached,
     * which is the opposite of the intended feel. `tanh` has a derivative of one at zero, so a small pull
     * moves the page by very nearly the same amount.
     */
    @Test
    fun `a small pull follows the finger almost exactly`() {
        val moved = resisted(pulled = 8f, limit = 200f)

        assertTrue(moved > 7.9f && moved <= 8f, "8px of pull moved the page $moved")
    }

    /**
     * **Pulling harder gives progressively less**, which is what makes the edge feel like an edge.
     *
     * Asserted as a comparison of two ratios rather than a magic number: the second half of a long pull
     * must move the page less than the first half did.
     */
    @Test
    fun `resistance grows with distance`() {
        val first = resisted(pulled = 100f, limit = 200f)
        val second = resisted(pulled = 200f, limit = 200f) - first

        assertTrue(second < first, "the second 100px moved $second, the first $first")
    }

    /**
     * **It never goes past the limit**, however hard it is pulled.
     *
     * The property that stops the page being dragged off the screen entirely. Mathematically an asymptote,
     * so there is no point at which the page stops responding — it just responds less; in `Float` it
     * saturates at the limit once the pull is a couple of dozen times it, which is the same guarantee by a
     * different route. Both are asserted, because the first is the feel and the second is the bound.
     */
    @Test
    fun `a very hard pull approaches the limit and never passes it`() {
        assertTrue(resisted(pulled = 400f, limit = 200f) < 200f, "a pull of twice the limit already maxed out")
        assertTrue(resisted(pulled = 400f, limit = 200f) > 190f, "a hard pull should get most of the way there")
        assertTrue(resisted(pulled = 5_000f, limit = 200f) <= 200f, "the page went past its own limit")
    }

    /** Both edges behave identically: the same pull the other way moves the page the same distance back. */
    @Test
    fun `the two edges are mirror images`() {
        assertEquals(-resisted(pulled = 120f, limit = 200f), resisted(pulled = -120f, limit = 200f))
    }

    /** A limit of zero is a page that cannot be pulled, rather than a division by zero. */
    @Test
    fun `a zero limit moves nothing`() {
        assertEquals(0f, resisted(pulled = 400f, limit = 0f))
    }
}
