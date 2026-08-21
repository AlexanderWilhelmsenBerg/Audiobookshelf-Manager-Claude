package com.example.shelfplayer.feature.book

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * PRODUCT_SPEC MGR-007 — the warning and the status, which are two of the six acceptance criteria.
 *
 * *"The UI warns that the server will modify source audio files"* and *"the app advises the user to maintain
 * server-side backups"* are both assertions about words on a screen, so they are tested as words on a
 * screen. A dialog that lost the backup sentence in a later edit would otherwise fail nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class EmbedMetadataScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** MGR-007: the warning has to name what is being rewritten, and it has to name the book. */
    @Test
    fun `the confirmation says the server rewrites the audio files`() {
        composeRule.setContent {
            EmbedMetadataDialog(title = "The Salt Harbour", onConfirm = {}, onDismiss = {})
        }

        composeRule.onNodeWithText("rewrite the audio files", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("The Salt Harbour", substring = true).assertIsDisplayed()
    }

    /** MGR-007: *"the app advises the user to maintain server-side backups"*, in as many words. */
    @Test
    fun `the confirmation advises server-side backups`() {
        composeRule.setContent {
            EmbedMetadataDialog(title = "A Book", onConfirm = {}, onDismiss = {})
        }

        composeRule.onNodeWithText("server-side backups", substring = true).assertIsDisplayed()
    }

    /**
     * The reassurance that matters most, because it is the fear: this app must never look as though it
     * touches the listener's downloads or their position.
     */
    @Test
    fun `the confirmation says nothing on the device changes`() {
        composeRule.setContent {
            EmbedMetadataDialog(title = "A Book", onConfirm = {}, onDismiss = {})
        }

        composeRule.onNodeWithText("Nothing on this device changes", substring = true).assertIsDisplayed()
    }

    /**
     * MGR-007 offers *"metadata only, cover only, or both if the API supports it"*, and it does not.
     *
     * So the dialog says the two go together. Asserted because the alternative a reader would expect is a
     * chooser, and its absence has to be explained rather than merely be true.
     */
    @Test
    fun `the confirmation says the cover cannot be chosen separately`() {
        composeRule.setContent {
            EmbedMetadataDialog(title = "A Book", onConfirm = {}, onDismiss = {})
        }

        composeRule.onNodeWithText("no way to choose one without the other", substring = true).assertIsDisplayed()
    }

    @Test
    fun `confirming reports it once`() {
        var confirmed = 0
        composeRule.setContent {
            EmbedMetadataDialog(title = "A Book", onConfirm = { confirmed++ }, onDismiss = {})
        }

        composeRule.onNodeWithText("Embed metadata").performClick()

        assertEquals(1, confirmed)
    }

    /** MGR-007: *"non-blocking, with visible status"*. The running state says so and cannot be dismissed. */
    @Test
    fun `a running embed is visible and cannot be dismissed`() {
        composeRule.setContent {
            EmbedStatusBanner(status = EmbedStatus.Running, onDismiss = {})
        }

        composeRule.onNodeWithText("writing metadata into", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").assertDoesNotExist()
    }

    /**
     * The state this feature exists to be honest about.
     *
     * A dropped connection is neither success nor failure, and the words have to say which of the two it is
     * not. This is the assertion that stops somebody "simplifying" it into a failure or a success later.
     */
    @Test
    fun `a dropped connection says the outcome is unknown, and where to look`() {
        composeRule.setContent {
            EmbedStatusBanner(status = EmbedStatus.Unknown, onDismiss = {})
        }

        composeRule.onNodeWithText("cannot tell how it ended", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Check the book on your server", substring = true).assertIsDisplayed()
    }

    /** A failure the server explained, and one it did not, are different sentences. */
    @Test
    fun `a server failure says whether there is a reason to go and read`() {
        composeRule.setContent {
            EmbedStatusBanner(status = EmbedStatus.ServerFailed(hasServerError = true), onDismiss = {})
        }
        composeRule.onNodeWithText("check its logs", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a silent server failure says there is no reason`() {
        composeRule.setContent {
            EmbedStatusBanner(status = EmbedStatus.ServerFailed(hasServerError = false), onDismiss = {})
        }
        composeRule.onNodeWithText("gave no reason", substring = true).assertIsDisplayed()
    }

    /** Nothing is drawn when there is nothing happening. */
    @Test
    fun `an idle embed draws nothing`() {
        composeRule.setContent {
            EmbedStatusBanner(status = EmbedStatus.Idle, onDismiss = {})
        }

        composeRule.onNodeWithText("Dismiss").assertDoesNotExist()
    }

    /** A terminal state is cleared by the user, not by a timer — two of them ask them to go and check. */
    @Test
    fun `a finished embed can be dismissed`() {
        var dismissed = 0
        composeRule.setContent {
            EmbedStatusBanner(status = EmbedStatus.Finished, onDismiss = { dismissed++ })
        }

        composeRule.onNodeWithText("Dismiss").performClick()

        assertEquals(1, dismissed)
    }
}
