package com.example.shelfplayer.feature.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputLockStatus
import com.example.shelfplayer.core.model.playback.DeviceKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioOutputLockChooserTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `ineligible unlocked output offers neither Keep nor Release`() {
        render(OutputControls(listOf(output), null, {}))

        compose.onNodeWithText(label(R.string.player_output_keep_here)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.player_output_release_lock)).assertDoesNotExist()
    }

    @Test
    fun `refused stale click remains visible with an explanation`() {
        var attempts = 0
        var closed = 0
        render(
            OutputControls(
                listOf(output),
                null,
                {},
                canKeepAudioHere = true,
                onKeepAudioHere = {
                    attempts++
                    false
                },
            ),
            onExpandedChange = { if (!it) closed++ },
        )

        compose.onNodeWithText(label(R.string.player_output_keep_here)).performClick()

        compose.onNodeWithText(label(R.string.player_output_lock_refused)).assertExists()
        assertEquals(1, attempts)
        assertEquals(0, closed)
    }

    @Test
    fun `waiting lock can be released with no connected output`() {
        var releases = 0
        render(
            OutputControls(
                emptyList(),
                "absent",
                {},
                lockStatus = AudioOutputLockStatus.Waiting,
                onReleaseLock = { releases++ },
            ),
        )

        compose.onNodeWithText(label(R.string.player_output_waiting_here)).assertExists()
        compose.onNodeWithText(label(R.string.player_output_release_lock)).performClick()
        assertEquals(1, releases)
    }

    private fun render(controls: OutputControls, onExpandedChange: (Boolean) -> Unit = {}) {
        compose.setContent {
            MaterialTheme { AudioOutputAction(controls, expanded = true, onExpandedChange) }
        }
    }

    private fun label(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private val output = AudioOutput("live", "Current output", DeviceKind.Bluetooth)
}
