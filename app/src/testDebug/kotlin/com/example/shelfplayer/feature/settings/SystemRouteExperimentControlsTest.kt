package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.shelfplayer.playback.HeadsetOutputCase
import com.example.shelfplayer.playback.HeadsetTestResult
import com.example.shelfplayer.playback.SystemRouteExperimentState
import com.example.shelfplayer.playback.SystemRouteRow
import com.example.shelfplayer.playback.SystemRouteStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemRouteExperimentControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pairedDriveCasesApplyIndividuallyToThePausedBook() {
        val applied = mutableListOf<HeadsetOutputCase>()
        compose.setContent {
            var active by remember { mutableStateOf(HeadsetOutputCase.Normal) }
            Column {
                HeadsetOutputControls(active, enabled = true) {
                    applied += it
                    active = it
                    HeadsetTestResult.Applied
                }
            }
        }
        for ((label, case) in listOf(
            "500 ms buffer" to HeadsetOutputCase.Disconnect500,
            "1,000 ms buffer" to HeadsetOutputCase.Disconnect1000,
            "Take-back off" to HeadsetOutputCase.ConnectTakeBackOff,
            "Take-back on" to HeadsetOutputCase.ConnectTakeBackOn,
        )) {
            val count = applied.size
            compose.onNodeWithText(label).performClick()
            assertEquals(count, applied.size)
            compose.onNodeWithText("Apply to paused book").performClick()
            assertEquals(case, applied.last())
            compose.onNodeWithText("Active output test · $label").assertExists()
        }
    }

    @Test
    fun comparisonToggleChangesOnlyTheAutomaticExperimentControl() {
        val transfers = mutableListOf<String>()
        val comparison = mutableListOf<Boolean>()
        compose.setContent {
            Column {
                SystemRouteExperimentControls(
                    SystemRouteExperimentState(),
                    onToggle = {},
                    onTransfer = transfers::add,
                    automaticTakeBack = true,
                    onAutomaticTakeBackChange = comparison::add,
                )
            }
        }
        compose.onNodeWithTag("system-route-automatic-toggle").performClick()
        assertEquals(listOf(false), comparison)
        assertTrue(transfers.isEmpty())
    }

    @Test
    fun showingRoutesNeverTransfersAndOnlyExplicitEligibleButtonSubmitsAlias() {
        val transfers = mutableListOf<String>()
        compose.setContent {
            Column {
                SystemRouteExperimentControls(
                    SystemRouteExperimentState(
                        status = SystemRouteStatus.Observing,
                        routes = listOf(SystemRouteRow("r1", "Headset", false), SystemRouteRow("r2", "Car", true)),
                    ),
                    onToggle = {},
                    onTransfer = transfers::add,
                )
            }
        }
        assertTrue(transfers.isEmpty())
        compose.onNodeWithTag("system-route-r2").assertIsNotEnabled()
        compose.onNodeWithTag("system-route-r1").performClick()
        assertEquals(listOf("r1"), transfers)
    }
}
