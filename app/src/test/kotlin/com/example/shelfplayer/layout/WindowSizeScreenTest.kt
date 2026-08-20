package com.example.shelfplayer.layout

import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.core.designsystem.layout.READING_WIDTH
import com.example.shelfplayer.core.designsystem.layout.centredListPadding
import com.example.shelfplayer.core.designsystem.layout.hasRoomForTwoPanes
import com.example.shelfplayer.core.designsystem.layout.windowWidth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC 4 / §129 — the breakpoints, at the three widths the product names.
 *
 * These are what every adaptive screen rests on, so they are worth testing apart from any one screen:
 * each two-pane layout in the app asks a single question — *is this window Expanded* — and if the answer
 * is wrong then every screen is wrong in the same way, which no individual screen's test would localise.
 *
 * Robolectric's `qualifiers` set the window's width, and that width reaches `LocalWindowInfo` — which is
 * what `windowSizeClass` reads. That is the other half of why it is derived from the window rather than
 * from an `Activity`: a device width becomes a test parameter instead of a device.
 *
 * In `:app` rather than in `:core:designsystem` because this is where the Compose test infrastructure
 * lives — the launcher activity `createComposeRule` needs is a `debugImplementation` of this module, and
 * the `ScreenTest` suffix is this module's contract for excluding such tests from the release variant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowSizeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** A phone. The overwhelmingly common case, and the one every layout defaults to. */
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a phone is compact`() {
        assertEquals(WindowWidthSizeClass.Compact, widthUnderTest())
    }

    /** A large foldable open, or one pane of a split tablet. */
    @Test
    @Config(qualifiers = "w700dp-h1000dp")
    fun `a seven-hundred-dp window is medium`() {
        assertEquals(WindowWidthSizeClass.Medium, widthUnderTest())
    }

    /** A tablet in landscape. */
    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `a tablet in landscape is expanded`() {
        assertEquals(WindowWidthSizeClass.Expanded, widthUnderTest())
    }

    /**
     * The decision every two-pane screen makes, at all three widths in one test, because the interesting
     * part is the *boundary*: Medium is the width somebody would reasonably expect to split, and this app
     * deliberately does not. A regression here would be somebody "fixing" that.
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `only an expanded window has room for two panes`() {
        assertFalse(WindowWidthSizeClass.Compact.hasRoomForTwoPanes)
        assertFalse(WindowWidthSizeClass.Medium.hasRoomForTwoPanes)
        assertTrue(WindowWidthSizeClass.Expanded.hasRoomForTwoPanes)
    }

    /** A phone gets no side padding: the window *is* the readable measure. */
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a compact window is not padded`() {
        assertEquals(0.dp, startPaddingFor(WindowWidthSizeClass.Compact))
    }

    /**
     * A wide window pads the list until the column is [READING_WIDTH] across.
     *
     * The number is asserted rather than "greater than zero", because the failures worth catching are a
     * sign error and a missing halving — both of which produce a non-zero padding and a broken layout.
     */
    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `an expanded window pads a list down to the reading width`() {
        assertEquals((1280.dp - READING_WIDTH) / 2, startPaddingFor(WindowWidthSizeClass.Expanded))
    }

    /**
     * A window narrower than the reading measure is never padded *negatively*.
     *
     * Reachable in practice: a 480dp window can be reported Expanded by nothing today, but the padding is
     * computed from the window rather than from the bucket, and a future breakpoint change would make the
     * combination real. `coerceAtLeast` is the guard; this is what proves it is there.
     */
    @Test
    @Config(qualifiers = "w480dp-h800dp")
    fun `a window narrower than the reading width is not padded`() {
        assertEquals(0.dp, startPaddingFor(WindowWidthSizeClass.Expanded))
    }

    private fun widthUnderTest(): WindowWidthSizeClass {
        var width: WindowWidthSizeClass? = null
        render { width = windowWidth() }
        return requireNotNull(width)
    }

    private fun startPaddingFor(width: WindowWidthSizeClass): Dp {
        var start: Dp? = null
        render { start = centredListPadding(width).calculateStartPadding(LayoutDirection.Ltr) }
        return requireNotNull(start)
    }

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent { content() }
        composeRule.waitForIdle()
    }
}
