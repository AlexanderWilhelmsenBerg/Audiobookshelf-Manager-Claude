package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.shelfplayer.core.model.download.NetworkPolicy
import com.example.shelfplayer.core.model.playback.BufferPreset
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.model.playback.PlaybackSpeed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The Playback refresh is intentionally compact: current values first, choices only after a tap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PlaybackSettingsTabScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `default speed is collapsed to its current value`() {
        render()

        composeRule.onNodeWithContentDescription("Default speed, 1×").assertIsDisplayed()
        composeRule.onNodeWithText("1.25×").assertDoesNotExist()
    }

    @Test
    fun `a playback choice expands inline and reports the selection`() {
        var chosen: PlaybackSpeed? = null
        render(
            actions = actions(onSpeedChanged = { chosen = it }),
        )

        composeRule.onNodeWithContentDescription("Default speed, 1×").performClick()
        composeRule.onNodeWithText("1.25×").assertIsDisplayed()
        composeRule.onNodeWithText("1.25×").performClick()

        assertEquals(PlaybackSpeed.of(1.25f), chosen)
        composeRule.onNodeWithText("1.25×").assertDoesNotExist()
    }

    @Test
    fun `buffer presets are hidden until buffer is opened`() {
        render()

        composeRule.onNodeWithText("Very high").assertDoesNotExist()
        scrollTo("Buffer")
        composeRule.onNodeWithContentDescription("Buffer, Automatic").performClick()
        composeRule.onNodeWithText("Very high").assertIsDisplayed()
    }

    @Test
    fun `playback tab contains no old explanatory paragraphs`() {
        render()

        listOf(
            "Used by every book that has not been given its own speed",
            "Wi-Fi is always used when it is available",
            "BookWave appears in Android Auto",
            "Audiobookshelf web interface",
        ).forEach { copy ->
            composeRule.onNodeWithText(copy, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun `network controls keep their existing defaults`() {
        render(networkPolicy = NetworkPolicy.Default)

        scrollTo("Download next book over mobile data")
        composeRule.onNodeWithText("Stream over mobile data").assertIsOn()
        composeRule.onNodeWithText("Download over mobile data").assertIsOff()
        composeRule.onNodeWithText("Download next book over mobile data").assertIsOff()
    }

    @Test
    fun `buffer selection is still forwarded`() {
        var chosen: BufferPreset? = null
        render(actions = actions(onBufferChanged = { chosen = it }))

        scrollTo("Buffer")
        composeRule.onNodeWithContentDescription("Buffer, Automatic").performClick()
        composeRule.onNodeWithText("High").performClick()

        assertEquals(BufferPreset.High, chosen)
    }

    private fun scrollTo(text: String) =
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))

    private fun render(
        actions: PlaybackSettingsActions = actions(),
        networkPolicy: NetworkPolicy = NetworkPolicy.Default,
    ) {
        composeRule.setContent {
            LazyColumn {
                playbackTab(
                    settings = PlaybackSettings.Default,
                    libraries = emptyList(),
                    actions = actions,
                    networkPolicy = networkPolicy,
                )
            }
        }
    }

    private fun actions(
        onSpeedChanged: (PlaybackSpeed) -> Unit = {},
        onBufferChanged: (BufferPreset) -> Unit = {},
    ): PlaybackSettingsActions = PlaybackSettingsActions(
        onSpeedChanged = onSpeedChanged,
        onSkipsChanged = {},
        onAutoRewindChanged = {},
        onBufferChanged = onBufferChanged,
    )
}
