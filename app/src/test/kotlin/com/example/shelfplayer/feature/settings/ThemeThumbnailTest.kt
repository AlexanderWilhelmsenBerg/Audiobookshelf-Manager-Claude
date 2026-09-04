package com.example.shelfplayer.feature.settings

import androidx.compose.ui.graphics.Color
import com.example.shelfplayer.core.model.settings.AppTheme
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC SET-002 (Appearance) — which ground previews which theme in the merged theme list.
 *
 * ### Why a test for four colours
 *
 * Because a thumbnail is decorative — it carries no `contentDescription`, deliberately, so the row it sits
 * in is announced once rather than twice — and that puts it out of reach of every screen test in this
 * project. The mapping is the half that can still be pinned: which colour goes with which theme, and that
 * AMOLED's is true black rather than merely dark. That the swatch is *drawn* is a device check, and
 * `docs/gaps.md` is where visual claims this suite cannot make are recorded.
 */
class ThemeThumbnailTest {

    /** White for light, near-black for dark, and the two of them side by side for *System*. */
    @Test
    fun `each theme previews the ground it paints`() {
        assertEquals(listOf(Color.White), AppTheme.Light.thumbnailGrounds())
        assertEquals(listOf(Color.Black), AppTheme.Amoled.thumbnailGrounds())
        assertEquals(1, AppTheme.Dark.thumbnailGrounds().size)
        assertEquals(2, AppTheme.System.thumbnailGrounds().size)
    }

    /**
     * **Dark is dark grey and AMOLED is `#000000`, and they are not the same colour.**
     *
     * The distinction is the entire difference between the two themes, so a preview that drew both as the
     * same near-black would make the one a reader is looking for unfindable. AMOLED's has to be true black
     * because an unlit pixel is what that theme is for.
     */
    @Test
    fun `dark and AMOLED preview as different grounds`() {
        val dark = AppTheme.Dark.thumbnailGrounds().single()
        val amoled = AppTheme.Amoled.thumbnailGrounds().single()

        assertEquals(Color.Black, amoled)
        assertTrue(dark != amoled, "Dark previews as true black, which is AMOLED's")
        assertTrue(dark.luminance() < MIDPOINT, "Dark does not preview as a dark ground")
    }

    /** *System* shows both, in light-then-dark order, so the split reads as "either of these". */
    @Test
    fun `system previews both grounds, light first`() {
        val (first, second) = AppTheme.System.thumbnailGrounds()

        assertTrue(first.luminance() > MIDPOINT, "the first half is not the light ground")
        assertTrue(second.luminance() < MIDPOINT, "the second half is not the dark ground")
    }

    /** Rec. 709, the same weighting `ShelfPlayerTheme.contrastOn` splits on. */
    private fun Color.luminance(): Double = RED * red + GREEN * green + BLUE * blue

    private companion object {
        const val RED = 0.2126
        const val GREEN = 0.7152
        const val BLUE = 0.0722
        const val MIDPOINT = 0.5
    }
}
