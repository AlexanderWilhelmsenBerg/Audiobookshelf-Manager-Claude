package com.example.shelfplayer.feature.player

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputActivitySource
import com.example.shelfplayer.core.model.playback.AudioOutputLockStatus
import com.example.shelfplayer.core.model.playback.AudioOutputPresentation
import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.core.model.playback.OutputDestination
import com.example.shelfplayer.core.model.playback.OutputMeasurement
import com.example.shelfplayer.core.model.playback.OutputTransferStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `previous choice during transfer is recovery context and remains releasable`() {
        var releases = 0
        render(
            OutputControls(
                outputs = emptyList(),
                selectedId = null,
                onSelect = {},
                lockStatus = AudioOutputLockStatus.Active,
                onReleaseLock = { releases++ },
                presentation = AudioOutputPresentation(
                    requested = OutputDestination.Car,
                    requestedOutputId = null,
                    measurement = OutputMeasurement.Unavailable,
                    transfer = OutputTransferStatus.Verifying,
                    hasRetainedPreviousLock = true,
                ),
            ),
        )
        compose.onNodeWithText(label(R.string.player_output_previous_retained)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.player_output_kept_here)).assertDoesNotExist()
        recordChooserScreenshot("retained-choice")
        compose.onNodeWithText(label(R.string.player_output_release_lock)).performClick()
        assertEquals(1, releases)
    }

    @Test
    fun `Car intent is distinct from Automatic and both remain reachable with no outputs`() {
        var selectedCar = 0
        val selectedIds = mutableListOf<String?>()
        render(
            OutputControls(
                outputs = emptyList(),
                selectedId = null,
                onSelect = { selectedIds += it },
                presentation = AudioOutputPresentation(
                    requested = OutputDestination.Car,
                    requestedOutputId = null,
                    measurement = OutputMeasurement.Unavailable,
                ),
                onSelectCar = { selectedCar++ },
            ),
        )

        compose.onAllNodesWithContentDescription(label(R.string.player_output_selected)).assertCountEquals(1)
        compose.onNodeWithText(label(R.string.player_output_car)).assertIsSelected()
        compose.onNodeWithText(label(R.string.player_output_automatic)).assertIsNotSelected()
        compose.onNodeWithText(label(R.string.player_output_unavailable)).assertExists()
        compose.onNodeWithText(label(R.string.player_output_car)).performClick()
        assertEquals(1, selectedCar)
        compose.onNodeWithText(label(R.string.player_output_automatic)).performClick()
        assertEquals(listOf<String?>(null), selectedIds)
    }

    @Test
    fun `multiple measured sinks and delegated or failed transfer stay explicitly unverified`() {
        render(
            OutputControls(
                outputs = emptyList(),
                selectedId = null,
                onSelect = {},
                presentation = AudioOutputPresentation(
                    requested = OutputDestination.Car,
                    requestedOutputId = null,
                    measurement = OutputMeasurement.Multiple,
                    transfer = OutputTransferStatus.Delegated,
                ),
            ),
        )

        compose.onNodeWithText(label(R.string.player_output_multiple)).assertExists()
        compose.onNodeWithText(label(R.string.player_output_delegated)).assertExists()
    }

    @Test
    fun `framework prediction is not shown as measured and a matching track endpoint is measured`() {
        render(
            OutputControls(
                outputs = listOf(
                    output.copy(isActive = true, activitySource = AudioOutputActivitySource.FrameworkMediaPolicy),
                ),
                selectedId = output.id,
                onSelect = {},
                presentation = AudioOutputPresentation(
                    requested = OutputDestination.Output,
                    requestedOutputId = output.id,
                    measurement = OutputMeasurement.Unavailable,
                ),
            ),
        )

        compose.onNodeWithText(output.displayName).assertExists()
        compose.onNodeWithText(label(R.string.player_output_playing_here, output.displayName)).assertDoesNotExist()
    }

    @Test
    fun `current endpoint proof and failed transfer are presented separately`() {
        render(
            OutputControls(
                outputs = listOf(output),
                selectedId = output.id,
                onSelect = {},
                presentation = AudioOutputPresentation(
                    requested = OutputDestination.Output,
                    requestedOutputId = output.id,
                    measurement = OutputMeasurement.Endpoint,
                    measuredOutputId = output.id,
                    transfer = OutputTransferStatus.Failed,
                ),
            ),
        )

        compose.onNodeWithText(label(R.string.player_output_playing_here, output.displayName)).assertExists()
        compose.onNodeWithText(label(R.string.player_output_transfer_failed)).assertExists()
    }

    @Test
    fun `waiting verifying and failed transfer status are announced`() {
        var currentPhase by mutableStateOf(OutputTransferStatus.Waiting)
        compose.setContent {
            MaterialTheme {
                AudioOutputAction(
                    controls = OutputControls(
                        outputs = emptyList(),
                        selectedId = null,
                        onSelect = {},
                        presentation = AudioOutputPresentation(
                            requested = OutputDestination.Car,
                            requestedOutputId = null,
                            measurement = OutputMeasurement.Multiple,
                            transfer = currentPhase,
                        ),
                    ),
                    expanded = true,
                    onExpandedChange = {},
                )
            }
        }
        val statuses = listOf(
            OutputTransferStatus.Waiting to R.string.player_output_transfer_waiting,
            OutputTransferStatus.Verifying to R.string.player_output_transfer_verifying,
            OutputTransferStatus.Failed to R.string.player_output_transfer_failed,
        )
        statuses.forEach { (phase, stringId) ->
            compose.runOnIdle { currentPhase = phase }
            compose.onNodeWithText(label(stringId)).assertExists()
        }
    }

    @Test
    fun `chooser and request callbacks survive a landscape configuration change`() {
        var configuration by mutableStateOf(
            Configuration(RuntimeEnvironment.getApplication().resources.configuration),
        )
        var carSelections = 0
        val automaticSelections = mutableListOf<String?>()
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MaterialTheme {
                    AudioOutputAction(
                        controls = OutputControls(
                            outputs = emptyList(),
                            selectedId = null,
                            onSelect = { automaticSelections += it },
                            presentation = AudioOutputPresentation(
                                requested = OutputDestination.Car,
                                requestedOutputId = null,
                                measurement = OutputMeasurement.Unavailable,
                            ),
                            onSelectCar = { carSelections++ },
                        ),
                        expanded = true,
                        onExpandedChange = {},
                    )
                }
            }
        }

        compose.runOnIdle {
            configuration = Configuration(configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = 640
                screenHeightDp = 360
            }
        }
        compose.onNodeWithText(label(R.string.player_output_car)).assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.player_output_automatic)).assertIsDisplayed().performClick()

        assertEquals(1, carSelections)
        assertEquals(listOf<String?>(null), automaticSelections)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w375dp-h812dp", fontScale = 2.0f)
    fun `long measured output wraps and remains reachable at doubled font scale`() {
        val longOutputName = "Bluetooth audiobook headphones in the passenger compartment"
        val measuredLabel = label(R.string.player_output_playing_here, longOutputName)
        renderAtLargeFont(
            OutputControls(
                outputs = listOf(output.copy(displayName = longOutputName)),
                selectedId = output.id,
                onSelect = {},
                presentation = AudioOutputPresentation(
                    requested = OutputDestination.Output,
                    requestedOutputId = output.id,
                    measurement = OutputMeasurement.Endpoint,
                    measuredOutputId = output.id,
                ),
            ),
        )

        val node = compose.onNodeWithText(measuredLabel)
        node.assertIsDisplayed()
        val actualText = node.fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .single()
            .text
        assertEquals(measuredLabel, actualText)
        val longHeight = node.fetchSemanticsNode().size.height
        val shortHeight = compose.onNodeWithText(label(R.string.player_output_automatic))
            .fetchSemanticsNode().size.height
        assertTrue(longHeight > shortHeight, "long measured-output status should wrap at the doubled font scale")
        recordChooserScreenshot("large-font")
    }

    @OptIn(InternalComposeUiApi::class)
    private fun recordChooserScreenshot(name: String) {
        val popup = compose.onNode(isRoot() and hasAnyDescendant(hasText(label(R.string.player_output_car))))
        val view = (popup.fetchSemanticsNode().root as ViewRootForTest).view
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            bitmap = createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        val destination = File("build/reports/output-presentation/$name.png")
        requireNotNull(destination.parentFile).mkdirs()
        destination.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun render(controls: OutputControls, onExpandedChange: (Boolean) -> Unit = {}) {
        compose.setContent {
            MaterialTheme { AudioOutputAction(controls, expanded = true, onExpandedChange) }
        }
    }

    private fun renderAtLargeFont(controls: OutputControls) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale = 2f),
            ) {
                MaterialTheme {
                    AudioOutputAction(
                        controls = controls,
                        expanded = true,
                        onExpandedChange = {},
                        modifier = Modifier.width(320.dp),
                    )
                }
            }
        }
    }

    private fun label(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private fun label(id: Int, value: String): String = RuntimeEnvironment.getApplication().getString(id, value)

    private val output = AudioOutput("live", "Current output", DeviceKind.Bluetooth)
}
