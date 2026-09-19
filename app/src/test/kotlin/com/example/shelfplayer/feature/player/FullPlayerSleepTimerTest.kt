package com.example.shelfplayer.feature.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.playback.PlaybackUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * BW-SLEEP-01 — reaches the actual full-player control rather than testing a presentation helper.
 *
 * The timer is ordinary Compose-observed owner state: there is no UI ticker here. Replacing it models the
 * same emissions MainActivity collects from Playback & Lifecycle for manual start, scheduled start, extend,
 * cancel and expiry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class FullPlayerSleepTimerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `active timer replaces sleep action in place and keeps the same interaction`() {
        var timer by mutableStateOf<SleepTimerState>(SleepTimerState.Idle)
        var opens = 0

        composeRule.setContent {
            FullPlayer(
                state = PlaybackUiState.Idle,
                timer = timer,
                actions = actions(onOpenSleepTimer = { opens += 1 }),
            )
        }

        composeRule.onNodeWithContentDescription("Set a sleep timer").assertExists().performClick()
        assertEquals(1, opens)

        timer = active(12)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Set a sleep timer").assertDoesNotExist()
        composeRule.onNodeWithText("12:00").assertExists()
        composeRule
            .onNode(hasClickAction() and hasText("12:00") and hasContentDescription("Sleep timer:", substring = true))
            .performClick()
        assertEquals(2, opens)

        // An extension is a new authoritative state emission, not arithmetic in this composable.
        timer = active(18)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("18:00").assertExists()

        // Cancellation and expiry both return the owner to Idle, so the ordinary Sleep action returns.
        timer = SleepTimerState.Idle
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Set a sleep timer").assertExists()
    }

    @Test
    fun `recreated player projects an already active owner timer without inventing local state`() {
        composeRule.setContent {
            FullPlayer(
                state = PlaybackUiState.Idle,
                timer = active(7),
                actions = actions(),
            )
        }

        composeRule.onNodeWithText("07:00").assertExists()
        composeRule
            .onNode(hasClickAction() and hasText("07:00") and hasContentDescription("Sleep timer:", substring = true))
            .assertExists()
    }

    private fun actions(onOpenSleepTimer: () -> Unit = {}) = PlayerActions(
        onTogglePlayPause = {},
        onSeekTo = {},
        onOpenSpeed = {},
        onOpenSleepTimer = onOpenSleepTimer,
        onOpenChapters = {},
        onCollapse = {},
    )

    private fun active(minutes: Int) = SleepTimerState(
        mode = SleepTimerMode.Fixed(30.minutes),
        remaining = minutes.minutes,
        isFading = false,
    )
}
