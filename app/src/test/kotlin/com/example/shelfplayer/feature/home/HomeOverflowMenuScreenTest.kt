package com.example.shelfplayer.feature.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The app-level destinations moved into one discoverable, labelled Home overflow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeOverflowMenuScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `overflow groups all four destinations and dispatches Loopbound`() {
        var selected: String? = null
        compose.setContent {
            MaterialTheme {
                HomeOverflowMenu(
                    actions = noActions().copy(
                        onProfilesSelected = { selected = "profiles" },
                        onDownloadsSelected = { selected = "downloads" },
                        onLoopboundSelected = { selected = "loopbound" },
                        onSettingsSelected = { selected = "settings" },
                    ),
                )
            }
        }

        compose.onNodeWithContentDescription("More options").performClick()
        listOf("Profiles", "Downloads", "Loopbound", "Settings").forEach { label ->
            compose.onNodeWithText(label).assertExists()
        }

        compose.onNodeWithText("Loopbound").performClick()
        assertEquals("loopbound", selected)
    }
}
