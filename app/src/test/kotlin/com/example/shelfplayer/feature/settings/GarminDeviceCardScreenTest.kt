package com.example.shelfplayer.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.shelfplayer.garmin.GarminBookChoice
import com.example.shelfplayer.garmin.GarminDeviceUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w400dp-h1000dp", application = android.app.Application::class)
class GarminDeviceCardScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inlineExpansionReachesForceSyncAndPickerDispatchesTheSelectedBook() {
        var syncs = 0
        var selected: String? = null
        compose.setContent {
            MaterialTheme {
                GarminDeviceCard(
                    GarminDeviceActions(
                        state = GarminDeviceUi(
                            name = "Fixture watch",
                            paired = true,
                            choices = listOf(GarminBookChoice("book", "Fixture book")),
                        ),
                        onSync = { syncs++ },
                        onDownload = { selected = it },
                    ),
                )
            }
        }
        compose.onNodeWithText("Force sync").assertDoesNotExist()
        compose.onNodeWithText("Fixture watch").performClick()
        compose.onNodeWithText("Force sync").performClick()
        assertEquals(1, syncs)
        compose.onNodeWithText("New download").performClick()
        compose.onNodeWithText("Fixture book").performClick()
        assertEquals("book", selected)
        compose.onNodeWithText("Fixture book").assertDoesNotExist()
    }

    @Test fun unknownWatchInventoryIsNotClaimedAsAnEmptyDownloadedLibrary() {
        compose.setContent {
            MaterialTheme {
                GarminDeviceCard(GarminDeviceActions(state = GarminDeviceUi(name = "Fixture watch", paired = true)))
            }
        }
        compose.onNodeWithText("Fixture watch").performClick()
        compose.onNodeWithText("Downloads").performClick()
        compose.onNodeWithText("No watch inventory received yet.").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("No watch inventory received yet.").assertDoesNotExist()
    }
}
