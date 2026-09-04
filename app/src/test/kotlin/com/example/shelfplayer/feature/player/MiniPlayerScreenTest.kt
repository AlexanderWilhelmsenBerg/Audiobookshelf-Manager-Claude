package com.example.shelfplayer.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.playback.SkipIntervals
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.playback.PlaybackUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-001 / 17.1 — the mini player, rendered.
 *
 * TalkBack reads the semantics tree rather than pixels, so the content-description assertions below are
 * assertions about what a screen-reader user would hear — and they run on every build rather than
 * whenever somebody remembers to switch a screen reader on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MiniPlayerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * **The elapsed and remaining clock is drawn at a large font scale too.**
     *
     * A previous version hid it above a font scale of 1.3, reasoning that it shared the top strip with
     * the title and that a clock nobody can read is worth less than the title it sits on. The device
     * disagreed — *"the progress timers went away"* — and the premise did not survive measurement either:
     * the bar comes out within a few dp of its floor across the whole font range, so the room the
     * threshold was protecting was never in short supply. Long text scrolls instead of being cut off.
     *
     * At 2.0 rather than at 1.3, so the assertion covers the far end rather than the boundary the
     * threshold happened to sit on.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h740dp", fontScale = 2.0f)
    fun `the clock is still drawn at twice the font size`() {
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                MiniPlayer(
                    state = playing(),
                    timer = SleepTimerState.Idle,
                    onTogglePlayPause = {},
                    onStop = {},
                    onOpenSleepTimer = {},
                    onExpand = {},
                    skips = SkipControls(SkipIntervals.Default, {}, {}),
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        // Thirty minutes into a two-hour book: elapsed on the left, remaining on the right with its sign.
        // Exact matches, because "30:00" is also a substring of "-1:30:00" and would find both.
        composeRule.onNodeWithText("30:00").assertExists()
        composeRule.onNodeWithText("-1:30:00").assertExists()
    }

    /**
     * Nothing playing costs no space.
     *
     * Asserted through the transport control's absence rather than through a height: a bar that renders
     * empty would still push every screen up by its own padding.
     */
    @Test
    fun `nothing is rendered when no book is loaded`() {
        composeRule.setContent {
            MiniPlayer(
                state = PlaybackUiState.Idle,
                timer = SleepTimerState.Idle,
                onTogglePlayPause = {},
                onStop = {},
                onOpenSleepTimer = {},
                onExpand = {},
                skips = SkipControls.Inert,
            )
        }

        composeRule.onNodeWithContentDescription("Resume").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Pause").assertDoesNotExist()
    }

    /**
     * PRODUCT_SPEC PLAY-001 — **the bar is the height of its content, not the height of the window.**
     *
     * This is the regression, and it is the reason a height floor is not a free substitute for a fixed
     * height. The tap region that opens the player uses `fillMaxHeight` so that the whole visible row is
     * tappable — and a child that fills the height participates in measuring its parent, resolving against
     * the *incoming maximum*. With `height(68.dp)` that maximum was 68dp. With `heightIn(min = 68.dp)` it
     * is whatever the bar was placed in, which in `MainActivity` is an overlay `Box` filling the window.
     *
     * So the bar became the height of the screen: a full-window frosted surface over the app, and
     * `onHeightMeasured` handing every screen the window height as the space to scroll clear of. The fix is
     * `IntrinsicSize.Min` on the content row, which replaces that maximum with the content's own.
     *
     * Asserted against the root rather than against a number, so it says "does not fill its container"
     * rather than pinning a particular row height.
     */
    @Test
    fun `the bar takes its own height rather than its container's`() {
        var reported: Dp? = null
        composeRule.setContent { InAWindow { reported = it } }

        val barHeight = barHeight()
        val windowHeight = windowHeight()

        assertTrue(barHeight < windowHeight / 2, "the bar is $barHeight of a $windowHeight window")
        assertTrue(barHeight >= MINI_PLAYER_MIN_HEIGHT, "the floor is $MINI_PLAYER_MIN_HEIGHT, the bar is $barHeight")
        assertEquals(barHeight, reported, "every screen scrolls clear of whatever this reports")
    }

    /**
     * PRODUCT_SPEC 3.1 — and it **grows** at a large font scale, which is what the floor is for.
     *
     * The title and the author are two lines in a bar that was fixed at 68dp; at 200% they need more, and a
     * bar that refused to grow clipped the author. Compared against the same bar at the default scale
     * rather than against a number, because the number is a font metric and would have to be rewritten
     * whenever the type scale moved.
     */
    @Test
    @Config(sdk = [34], fontScale = 2.0f)
    fun `the bar grows past its floor at twice the font size`() {
        var reported: Dp? = null
        composeRule.setContent { InAWindow { reported = it } }

        val barHeight = barHeight()

        assertTrue(barHeight > MINI_PLAYER_MIN_HEIGHT, "the bar stayed at its floor of $MINI_PLAYER_MIN_HEIGHT")
        assertTrue(barHeight < windowHeight() / 2, "the bar is $barHeight and still filling")
        assertEquals(barHeight, reported)
        composeRule.onNodeWithText("The Tidewatch Cycle").assertIsDisplayed()
        composeRule.onNodeWithText("Marisol Holt").assertIsDisplayed()
    }

    @Test
    fun `a playing book shows its title and author`() {
        composeRule.setContent {
            MiniPlayer(
                state = playing(),
                timer = SleepTimerState.Idle,
                onTogglePlayPause = {},
                onStop = {},
                onOpenSleepTimer = {},
                onExpand = {},
                skips = SkipControls.Inert,
            )
        }

        composeRule.onNodeWithText("The Tidewatch Cycle").assertIsDisplayed()
        composeRule.onNodeWithText("Marisol Holt").assertIsDisplayed()
    }

    /**
     * The label a screen reader announces has to say what the button *does*.
     *
     * "Play" on a control that pauses is the mistake this pins: the description follows the state, so a
     * playing book offers "Pause" and a paused one offers "Resume".
     */
    @Test
    fun `the transport control announces what it will do`() {
        composeRule.setContent {
            MiniPlayer(
                state = playing(),
                timer = SleepTimerState.Idle,
                onTogglePlayPause = {},
                onStop = {},
                onOpenSleepTimer = {},
                onExpand = {},
                skips = SkipControls.Inert,
            )
        }

        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Resume").assertDoesNotExist()
    }

    @Test
    fun `a paused book offers to resume`() {
        composeRule.setContent {
            MiniPlayer(
                state = playing(isPlaying = false),
                timer = SleepTimerState.Idle,
                onTogglePlayPause = {},
                onStop = {},
                onOpenSleepTimer = {},
                onExpand = {},
                skips = SkipControls.Inert,
            )
        }

        composeRule.onNodeWithContentDescription("Resume").assertIsDisplayed()
    }

    /**
     * PRODUCT_SPEC PLAY-001 — tapping the bar opens the player; tapping a control does not.
     *
     * The second half is the one worth pinning. The bar carries a click of its own, and a button inside
     * a clickable parent that did not consume its own press would both pause the book *and* open the
     * player — which is the bug this asserts is absent.
     */
    @Test
    fun `tapping the bar opens the player and tapping a control does not`() {
        var expands = 0
        var toggles = 0
        composeRule.setContent {
            MiniPlayer(
                state = playing(),
                timer = SleepTimerState.Idle,
                onTogglePlayPause = { toggles++ },
                onStop = {},
                onOpenSleepTimer = {},
                onExpand = { expands++ },
                skips = SkipControls.Inert,
            )
        }

        composeRule.onNodeWithText("The Tidewatch Cycle").performClick()
        assertEquals(1, expands)
        assertEquals(0, toggles)

        composeRule.onNodeWithContentDescription("Pause").performClick()
        assertEquals(1, toggles)
        assertEquals(1, expands, "the button's press did not also open the player")
    }

    /** PRODUCT_SPEC PLAY-007 — both skips report their direction. */
    @Test
    fun `the skip controls report their direction`() {
        val skips = mutableListOf<String>()
        composeRule.setContent {
            MiniPlayer(
                state = playing(),
                timer = SleepTimerState.Idle,
                onTogglePlayPause = {},
                onStop = {},
                onOpenSleepTimer = {},
                onExpand = {},
                skips = SkipControls(
                    intervals = SkipIntervals.Default,
                    onBack = { skips += "back" },
                    onForward = { skips += "forward" },
                ),
            )
        }

        composeRule.onNodeWithContentDescription("Back 30 seconds").performClick()
        composeRule.onNodeWithContentDescription("Forward 30 seconds").performClick()

        assertEquals(listOf("back", "forward"), skips)
    }

    /**
     * PRODUCT_SPEC PLAY-007 — the labels follow the configured interval.
     *
     * The number is the part a screen reader announces and, where Material has a glyph for it, the part
     * drawn on the button. A control that says thirty and jumps forty-five is worse than one with no
     * number: the user has no reason to distrust it.
     */
    @Test
    fun `the skip labels follow the configured interval`() {
        composeRule.setContent {
            MiniPlayer(
                state = playing(),
                timer = SleepTimerState.Idle,
                onTogglePlayPause = {},
                onStop = {},
                onOpenSleepTimer = {},
                onExpand = {},
                skips = SkipControls.Inert.copy(
                    intervals = SkipIntervals.of(back = 10.seconds, forward = 45.seconds),
                ),
            )
        }

        composeRule.onNodeWithContentDescription("Back 10 seconds").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Forward 45 seconds").assertIsDisplayed()
    }

    @Test
    fun `the transport control and the stop control report to the caller`() {
        var toggles = 0
        var stops = 0
        composeRule.setContent {
            MiniPlayer(
                state = playing(),
                timer = SleepTimerState.Idle,
                onTogglePlayPause = { toggles++ },
                onStop = { stops++ },
                onOpenSleepTimer = {},
                onExpand = {},
                skips = SkipControls.Inert,
            )
        }

        composeRule.onNodeWithContentDescription("Pause").performClick()
        composeRule.onNodeWithContentDescription("Stop playback").performClick()

        assertEquals(1, toggles)
        assertEquals(1, stops)
    }

    /** A book starting is announced politely rather than interrupting whatever is being read. */
    @Test
    fun `the title is a polite live region`() {
        composeRule.setContent {
            MiniPlayer(
                state = playing(),
                timer = SleepTimerState.Idle,
                onTogglePlayPause = {},
                onStop = {},
                onOpenSleepTimer = {},
                onExpand = {},
                skips = SkipControls.Inert,
            )
        }

        composeRule.onNodeWithText("The Tidewatch Cycle").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion),
        )
    }

    /**
     * PRODUCT_SPEC PLAY-008 — a running timer shows its remaining time on the control itself.
     *
     * The countdown is the label, so it is also what a screen reader reads. Asserting the content
     * description rather than the text is what makes that true rather than incidental: "12 min" alone
     * announces a number with no noun attached to it.
     */
    @Test
    fun `a running timer shows its remaining time and says what it is`() {
        composeRule.setContent {
            MiniPlayer(
                state = playing(),
                timer = SleepTimerState(
                    mode = SleepTimerMode.Fixed(30.minutes),
                    remaining = 12.minutes,
                    isFading = false,
                ),
                onTogglePlayPause = {},
                onStop = {},
                onOpenSleepTimer = {},
                onExpand = {},
                skips = SkipControls.Inert,
            )
        }

        composeRule.onNodeWithContentDescription("Sleep timer: 12 min left").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Set a sleep timer").assertDoesNotExist()
    }

    /**
     * The rounding that stops a countdown reading as stuck.
     *
     * 61 seconds shows "2 min", not "1 min" twice — see `Duration.asShortLabel`.
     */
    @Test
    fun `the remaining time rounds up while minutes are shown`() {
        composeRule.setContent {
            MiniPlayer(
                state = playing(),
                timer = SleepTimerState(
                    mode = SleepTimerMode.Fixed(30.minutes),
                    remaining = 61.seconds,
                    isFading = false,
                ),
                onTogglePlayPause = {},
                onStop = {},
                onOpenSleepTimer = {},
                onExpand = {},
                skips = SkipControls.Inert,
            )
        }

        composeRule.onNodeWithContentDescription("Sleep timer: 2 min left").assertIsDisplayed()
    }

    /** With no timer running the control is the way to set one, and says so. */
    @Test
    fun `with no timer the control offers to set one`() {
        composeRule.setContent {
            MiniPlayer(
                state = playing(),
                timer = SleepTimerState.Idle,
                onTogglePlayPause = {},
                onStop = {},
                onOpenSleepTimer = {},
                onExpand = {},
                skips = SkipControls.Inert,
            )
        }

        composeRule.onNodeWithContentDescription("Set a sleep timer").assertIsDisplayed()
    }

    /** A book whose duration is not known yet must not divide by zero to draw its progress bar. */
    @Test
    fun `an unknown duration renders as no progress rather than crashing`() {
        composeRule.setContent {
            MiniPlayer(
                state = playing(position = 5.minutes, duration = Duration.ZERO),
                timer = SleepTimerState.Idle,
                onTogglePlayPause = {},
                onStop = {},
                onOpenSleepTimer = {},
                onExpand = {},
                skips = SkipControls.Inert,
            )
        }

        composeRule.onNodeWithText("The Tidewatch Cycle").assertIsDisplayed()
    }

    /**
     * The bar where `MainActivity` actually puts it: an overlay at the bottom of a `Box` filling the window.
     *
     * The arrangement is the point. A bar composed on its own has no container to fill, so the defect these
     * tests cover — a height floor letting a `fillMaxHeight` child resolve against the window — cannot
     * appear. The root node's bounds are the content's own, which is why the window is a tagged `Box`
     * rather than `onRoot`.
     */
    @Composable
    private fun InAWindow(onHeightMeasured: (Dp) -> Unit) {
        Box(modifier = Modifier.fillMaxSize().testTag(WINDOW_TEST_TAG)) {
            MiniPlayer(
                state = playing(),
                timer = SleepTimerState.Idle,
                onTogglePlayPause = {},
                onStop = {},
                onOpenSleepTimer = {},
                onExpand = {},
                skips = SkipControls.Inert,
                onHeightMeasured = onHeightMeasured,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    private fun barHeight(): Dp = composeRule.onNodeWithTag(MINI_PLAYER_TEST_TAG).getBoundsInRoot()
        .let { it.bottom - it.top }

    private fun windowHeight(): Dp = composeRule.onNodeWithTag(WINDOW_TEST_TAG).getBoundsInRoot()
        .let { it.bottom - it.top }

    private fun playing(isPlaying: Boolean = true, position: Duration = 30.minutes, duration: Duration = 2.hours) =
        PlaybackUiState(
            bookId = LibraryItemId("tidewatch"),
            title = "The Tidewatch Cycle",
            author = "Marisol Holt",
            artworkUri = null,
            isPlaying = isPlaying,
            isLoading = false,
            position = position,
            duration = duration,
        )

    private companion object {
        /** The container the bar is an overlay in, so a test can ask whether the bar filled it. */
        const val WINDOW_TEST_TAG = "test-window"
    }
}
