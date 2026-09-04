package com.example.shelfplayer.feature.player

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PRODUCT_SPEC PLAY-001 / 4 — what the mini player tells six screens to reserve for it.
 *
 * ### The device report this file exists for
 *
 * *"I am testing 72, it is taller."* The bar reports its own height so screens can scroll clear of it,
 * and the first version reported the height of the **surface** — which deliberately runs to the bottom of
 * the window, navigation bar included, so the glass has no unblurred strip beneath it. Every screen then
 * added the navigation bar again on top of that, because `playerChromeClearance` promises it is not in
 * there. Home, the book page, Settings, Downloads, a series and an author all gained a gap the size of the
 * gesture handle.
 *
 * It also read as a *constant* increase, which is what separated it from the bar's own growth: the
 * navigation bar does not change with the font scale, so the extra space did not either — exactly the
 * *"increasing font didn't increase height"* in the same report.
 */
class PlayerClearanceTest {

    /** The reported clearance is what the bar covers **above** the navigation bar, and nothing more. */
    @Test
    fun `the navigation bar is not part of the clearance`() {
        assertEquals(68.dp, playerClearanceOf(measuredHeight = 68.dp + 24.dp, systemBarInset = 24.dp))
    }

    /**
     * A gesture-navigation handle and a three-button bar are different sizes, and neither belongs in it.
     *
     * The two cases together are the point: the answer tracks the inset rather than assuming one device.
     */
    @Test
    fun `a taller navigation bar is still not part of the clearance`() {
        assertEquals(68.dp, playerClearanceOf(measuredHeight = 68.dp + 48.dp, systemBarInset = 48.dp))
    }

    /** With no navigation bar at all — a device on buttons-off, or a test — the height is the clearance. */
    @Test
    fun `with no navigation bar the clearance is the whole measurement`() {
        assertEquals(68.dp, playerClearanceOf(measuredHeight = 68.dp, systemBarInset = 0.dp))
    }

    /**
     * The bar growing for a large font grows the clearance with it, which is the mechanism's whole purpose.
     *
     * The floor stays 68dp; what is above it is the title and author asking for room, and the screens
     * below have to know about it or the last row of a settings list sits behind the player.
     */
    @Test
    fun `a bar that grew reports the growth`() {
        assertEquals(74.dp, playerClearanceOf(measuredHeight = 74.dp + 24.dp, systemBarInset = 24.dp))
    }

    /**
     * A measurement smaller than the inset reports zero rather than a negative.
     *
     * Not reachable through the layout — the surface has a floor of `68dp + inset` — but a clearance is a
     * distance, and a negative one subtracted from a list's content padding would pull its last row *up*
     * behind the player, which is the failure this whole value exists to prevent.
     */
    @Test
    fun `an implausible measurement clamps to zero`() {
        assertEquals(0.dp, playerClearanceOf(measuredHeight = 10.dp, systemBarInset = 24.dp))
    }
}
