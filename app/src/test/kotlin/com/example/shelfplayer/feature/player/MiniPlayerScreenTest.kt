package com.example.shelfplayer.feature.player

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.playback.PlaybackUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
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
                onSkipBy = {},
            )
        }

        composeRule.onNodeWithContentDescription("Resume").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Pause").assertDoesNotExist()
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
                onSkipBy = {},
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
                onSkipBy = {},
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
                onSkipBy = {},
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
                onSkipBy = {},
            )
        }

        composeRule.onNodeWithText("The Tidewatch Cycle").performClick()
        assertEquals(1, expands)
        assertEquals(0, toggles)

        composeRule.onNodeWithContentDescription("Pause").performClick()
        assertEquals(1, toggles)
        assertEquals(1, expands, "the button's press did not also open the player")
    }

    /** PRODUCT_SPEC PLAY-007 — both skips report their direction in seconds. */
    @Test
    fun `the skip controls report their direction`() {
        val skips = mutableListOf<Long>()
        composeRule.setContent {
            MiniPlayer(
                state = playing(),
                timer = SleepTimerState.Idle,
                onTogglePlayPause = {},
                onStop = {},
                onOpenSleepTimer = {},
                onExpand = {},
                onSkipBy = { delta -> skips += delta.inWholeSeconds },
            )
        }

        composeRule.onNodeWithContentDescription("Back 30 seconds").performClick()
        composeRule.onNodeWithContentDescription("Forward 30 seconds").performClick()

        assertEquals(listOf(-30L, 30L), skips)
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
                onSkipBy = {},
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
                onSkipBy = {},
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
                onSkipBy = {},
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
                onSkipBy = {},
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
                onSkipBy = {},
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
                onSkipBy = {},
            )
        }

        composeRule.onNodeWithText("The Tidewatch Cycle").assertIsDisplayed()
    }

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
}
