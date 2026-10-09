package com.example.shelfplayer.feature.settings

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.annotation.RequiresApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.createBitmap
import com.example.shelfplayer.garmin.GarminBookChoice
import com.example.shelfplayer.garmin.GarminDeviceUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w400dp-h1000dp", application = android.app.Application::class)
@SuppressLint("UseSdkSuppress") // Robolectric @Config owns SDK selection; the runner filter is absent from JVM tests.
@RequiresApi(34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
                            configured = true,
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

    @Test fun partialDownloadDisplaysBookPercentAndDispatchesExplicitResume() {
        var resumed: String? = null
        compose.setContent {
            MaterialTheme {
                GarminDeviceCard(
                    GarminDeviceActions(
                        state = GarminDeviceUi(
                            name = "Fixture watch",
                            paired = true,
                            configured = true,
                            downloads = listOf(
                                com.example.shelfplayer.garmin.GarminDownloadRow(
                                    "Interrupted fixture",
                                    "queued",
                                    1,
                                    4,
                                    bookId = "book",
                                    canResume = true,
                                ),
                            ),
                        ),
                        onResume = { resumed = it },
                    ),
                )
            }
        }
        compose.onNodeWithText("Fixture watch").performClick()
        compose.onNodeWithText("Downloads").performClick()
        compose.onNodeWithText("Interrupted fixture").assertIsDisplayed()
        compose.onNodeWithText("25% downloaded").assertIsDisplayed()
        compose.onNodeWithText("Resume download").performClick()
        assertEquals("book", resumed)
    }

    @Test fun unknownWatchInventoryIsNotClaimedAsAnEmptyDownloadedLibrary() {
        compose.setContent {
            MaterialTheme {
                GarminDeviceCard(
                    GarminDeviceActions(
                        state = GarminDeviceUi(name = "Fixture watch", paired = true, configured = true),
                    ),
                )
            }
        }
        compose.onNodeWithText("Fixture watch").performClick()
        compose.onNodeWithText("Downloads").performClick()
        compose.onNodeWithText("No watch inventory received yet.").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("No watch inventory received yet.").assertDoesNotExist()
    }

    @Test fun pendingCodeOffersRetryAndCancelInsteadOfLeavingThePhoneStuck() {
        var retries = 0
        var cancelled = 0
        compose.setContent {
            MaterialTheme {
                GarminDeviceCard(
                    GarminDeviceActions(
                        state = GarminDeviceUi(name = "Fixture watch", ready = true, pairingCode = "123456"),
                        onPair = { retries++ },
                        onCancelPairing = { cancelled++ },
                    ),
                )
            }
        }
        compose.onNodeWithText("Fixture watch").performClick()
        compose.onNodeWithText("Send new pairing code").performClick()
        compose.onNodeWithText("Cancel pairing").performClick()
        assertEquals(1, retries)
        assertEquals(1, cancelled)
    }

    @Test fun setupReopensWithTheSavedAddressWithoutRequiringSchemeEntry() {
        compose.setContent {
            MaterialTheme {
                GarminDeviceCard(
                    GarminDeviceActions(
                        state = GarminDeviceUi(
                            name = "Fixture watch",
                            paired = true,
                            ready = true,
                            profileId = "profile",
                            sidecarUrl = "https://example.invalid/sidecar",
                        ),
                    ),
                )
            }
        }
        compose.onNodeWithText("Fixture watch").performClick()
        compose.onNodeWithText("WatchShelf Sidecar setup").performClick()
        compose.onNodeWithText("example.invalid/sidecar").assertExists()
        compose.onNodeWithText("https://").assertExists()
    }

    @Test fun phoneSetupNeedsOnlyAnAddressAndNeverShowsCredentialFields() = setupAndCapture(
        "garmin-setup-400",
    )

    @Test
    @Config(qualifiers = "en-w320dp-h1000dp", fontScale = 2.0f)
    fun phoneSetupAtNarrowLargeTextKeepsFieldsAndActionsReachable() = setupAndCapture("garmin-setup-320-font2")

    private fun setupAndCapture(name: String) {
        var sent: String? = null
        compose.setContent {
            MaterialTheme {
                GarminDeviceCard(
                    GarminDeviceActions(
                        state = GarminDeviceUi(
                            name = "Fixture watch",
                            paired = true,
                            ready = true,
                            username = "fixture",
                            profileId = "profile",
                        ),
                        onConfigure = { url -> sent = url },
                    ),
                )
            }
        }
        compose.onNodeWithText("Fixture watch").performClick()
        compose.onNodeWithText("WatchShelf Sidecar setup").performClick()
        compose.onNodeWithText("Username").assertDoesNotExist()
        compose.onNodeWithText("Password (one-time use)").assertDoesNotExist()
        compose.onNodeWithTag("settings-glass-dialog").assertExists()
        lateinit var image: Bitmap
        compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().last()
            image = createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
        }
        val output = File("build/glass-settings-evidence").apply { mkdirs() }
        File(output, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText(
            "HTTPS Sidecar URL",
        ).performScrollTo().performTextInput("example.invalid/sidecar")
        compose.onNodeWithText("Send setup").performClick()
        assertEquals("example.invalid/sidecar", sent)
        compose.onNodeWithText("WatchShelf Sidecar setup").performClick()
        compose.onNodeWithText("Send setup").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("WatchShelf Sidecar setup").performClick()
        compose.onNodeWithText("Send setup").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
    }
}
