package com.example.shelfplayer.ui.gesture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PRODUCT_SPEC 16.2 — which place a swipe lands on, and where it deliberately stops.
 *
 * The gesture itself is a screen concern and is tested where the screens are. These two are the arithmetic
 * underneath it, and the property worth pinning is the *absence* of wrapping: a bar that shows the reader
 * where they are in a row of four must not be driven by a gesture that jumps from the last place to the
 * first.
 */
class SwipeBetweenTest {

    private val places = listOf("Books", "Series", "Authors", "Genres")

    @Test
    fun `next moves one to the right`() {
        assertEquals("Series", nextOf(places, "Books"))
        assertEquals("Genres", nextOf(places, "Authors"))
    }

    @Test
    fun `previous moves one to the left`() {
        assertEquals("Books", previousOf(places, "Series"))
        assertEquals("Authors", previousOf(places, "Genres"))
    }

    /** The end of the row is the end. A caller with somewhere else to go supplies it themselves. */
    @Test
    fun `neither end wraps`() {
        assertNull(previousOf(places, "Books"), "there is nothing before the first place")
        assertNull(nextOf(places, "Genres"), "there is nothing after the last place")
    }

    /**
     * A value the list does not hold answers `null` both ways rather than picking an end.
     *
     * `indexOf` returns `-1` for a miss, and `-1 + 1` is a valid index — so the naive form would report the
     * *first* place as the one after something that is not in the row at all.
     */
    @Test
    fun `an unknown place has no neighbours`() {
        assertNull(nextOf(places, "Nowhere"))
        assertNull(previousOf(places, "Nowhere"))
    }
}
