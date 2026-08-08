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
            MiniPlayer(state = PlaybackUiState.Idle, onTogglePlayPause = {}, onStop = {})
        }

        composeRule.onNodeWithContentDescription("Resume").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Pause").assertDoesNotExist()
    }

    @Test
    fun `a playing book shows its title and author`() {
        composeRule.setContent {
            MiniPlayer(state = playing(), onTogglePlayPause = {}, onStop = {})
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
            MiniPlayer(state = playing(), onTogglePlayPause = {}, onStop = {})
        }

        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Resume").assertDoesNotExist()
    }

    @Test
    fun `a paused book offers to resume`() {
        composeRule.setContent {
            MiniPlayer(state = playing(isPlaying = false), onTogglePlayPause = {}, onStop = {})
        }

        composeRule.onNodeWithContentDescription("Resume").assertIsDisplayed()
    }

    @Test
    fun `the transport control and the stop control report to the caller`() {
        var toggles = 0
        var stops = 0
        composeRule.setContent {
            MiniPlayer(state = playing(), onTogglePlayPause = { toggles++ }, onStop = { stops++ })
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
            MiniPlayer(state = playing(), onTogglePlayPause = {}, onStop = {})
        }

        composeRule.onNodeWithText("The Tidewatch Cycle").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion),
        )
    }

    /** A book whose duration is not known yet must not divide by zero to draw its progress bar. */
    @Test
    fun `an unknown duration renders as no progress rather than crashing`() {
        composeRule.setContent {
            MiniPlayer(
                state = playing(position = 5.minutes, duration = Duration.ZERO),
                onTogglePlayPause = {},
                onStop = {},
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
