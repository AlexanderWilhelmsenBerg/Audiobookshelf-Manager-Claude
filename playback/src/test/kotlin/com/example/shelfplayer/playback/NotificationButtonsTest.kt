package com.example.shelfplayer.playback

import androidx.media3.session.CommandButton
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-007 — the notification's skip glyphs never claim the wrong number.
 *
 * Same rule the in-app buttons follow (`SkipIcons`), and the same reason: a button with "30" drawn into it
 * that jumps forty-five seconds is worse than one with no number, because nothing tells the user to
 * distrust it. The amount is always in the display name, which is what a screen reader announces.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationButtonsTest {

    @Test
    fun `an interval Media3 has a glyph for gets the numbered glyph`() {
        assertEquals(CommandButton.ICON_SKIP_BACK_5, NotificationButtons.backIcon(5.seconds))
        assertEquals(CommandButton.ICON_SKIP_BACK_10, NotificationButtons.backIcon(10.seconds))
        assertEquals(CommandButton.ICON_SKIP_BACK_15, NotificationButtons.backIcon(15.seconds))
        assertEquals(CommandButton.ICON_SKIP_BACK_30, NotificationButtons.backIcon(30.seconds))
        assertEquals(CommandButton.ICON_SKIP_FORWARD_30, NotificationButtons.forwardIcon(30.seconds))
    }

    /** PLAY-007 allows 5–120 seconds; Media3 draws four of those. The rest get a plain arrow. */
    @Test
    fun `an interval with no glyph gets a plain arrow rather than a wrong number`() {
        assertEquals(CommandButton.ICON_SKIP_BACK, NotificationButtons.backIcon(45.seconds))
        assertEquals(CommandButton.ICON_SKIP_FORWARD, NotificationButtons.forwardIcon(90.seconds))
    }

    /** A fractional interval is not a 30-second one, however close. */
    @Test
    fun `an interval that is not a whole match is not given the numbered glyph`() {
        assertEquals(CommandButton.ICON_SKIP_BACK, NotificationButtons.backIcon(31.seconds))
    }
}
