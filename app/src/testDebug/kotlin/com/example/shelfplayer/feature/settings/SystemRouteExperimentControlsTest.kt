package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
