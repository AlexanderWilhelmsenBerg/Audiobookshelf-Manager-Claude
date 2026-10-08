package com.example.shelfplayer.feature.settings

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.model.playback.SleepTimerScheduleSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w320dp-h1000dp", application = android.app.Application::class)
class SleepScheduleCardScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun toggleAloneExpandsAndCollapsesTheTimeControls() {
        val state = mutableStateOf(SleepTimerScheduleSettings.Default)
        compose.setContent {
            MaterialTheme {
                SleepScheduleCard(
                    state.value,
                    SleepScheduleSettingsActions(onEnabledChanged = {
                        state.value =
                            state.value.copy(enabled = it)
                    }),
                )
            }
        }
        compose.onNodeWithText("Window starts", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Automatic nightly sleep timer").performClick()
        compose.onNodeWithText("Window starts", substring = true).assertExists()
        compose.onNodeWithText("Window ends", substring = true).assertExists()
        compose.onNodeWithText("Automatic nightly sleep timer").performClick()
        compose.onNodeWithText("Window starts", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Window ends", substring = true).assertDoesNotExist()
    }

    @Test fun glassTimeEditorCommitsTheSelectedStartAndCancelPreservesTheEnd() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
        val state = mutableStateOf(SleepTimerScheduleSettings.Default.copy(enabled = true))
        var start: LocalTime? = null
        var ends = 0
        compose.setContent {
            MaterialTheme {
                SleepScheduleCard(
                    state.value,
                    SleepScheduleSettingsActions(onStartChanged = {
                        start =
                            it
                    }, onEndChanged = { ends++ }),
                )
            }
        }
        compose.onNodeWithText("Window starts", substring = true).performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("23")
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("17")
        compose.onNodeWithText("OK").performClick()
        assertEquals(LocalTime.of(23, 17), start)
        compose.onNodeWithText("Window ends", substring = true).performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, ends)
    }
}
