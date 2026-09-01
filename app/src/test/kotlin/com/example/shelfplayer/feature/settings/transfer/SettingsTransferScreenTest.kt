package com.example.shelfplayer.feature.settings.transfer

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.settings.SettingsExport
import com.example.shelfplayer.core.model.settings.SettingsImport
import com.example.shelfplayer.domain.repository.SettingsTransferRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PRODUCT_SPEC SET-001 — what the settings-file controls say, and specifically what they do not claim.
 *
 * The sentence after an import is the part worth a test. A fresh install — the case the whole feature
 * exists for — restores the device-wide settings and can restore *no* per-account view preferences,
 * because the accounts they belong to have not signed in yet. A bare "settings imported" there would be
 * telling the user something that did not happen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SettingsTransferScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `both directions are offered and neither is busy`() {
        composeRule.setContent { SettingsTransferSection(viewModel = viewModel()) }

        composeRule.onNodeWithText("Export settings…").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("Import settings…").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `the sign-in screen's button says the file will not sign you in`() {
        composeRule.setContent { ImportSettingsButton(onServerPicked = {}, viewModel = viewModel()) }

        composeRule.onNodeWithText("Import settings from a file…").assertIsDisplayed()
        composeRule.onNodeWithText("It does not sign you in", substring = true).assertIsDisplayed()
    }

    /** An import that restored everything says so plainly, with no caveat to read. */
    @Test
    fun `an import that restored everything mentions no skipped accounts`() {
        composeRule.setContent {
            Text(
                importedSentence(
                    SettingsImport(
                        serverUrls = listOf("https://books.example"),
                        exportedAt = null,
                        profilePreferencesApplied = 1,
                        profilePreferencesSkipped = 0,
                    ),
                ),
            )
        }

        composeRule.onNodeWithText("Settings imported, including 1 server address.").assertIsDisplayed()
    }

    /** And one on a fresh install names what is waiting on a sign-in. */
    @Test
    fun `an import on a fresh install says which preferences are still waiting`() {
        composeRule.setContent {
            Text(
                importedSentence(
                    SettingsImport(
                        serverUrls = listOf("https://books.example"),
                        exportedAt = null,
                        profilePreferencesApplied = 0,
                        profilePreferencesSkipped = 2,
                    ),
                ),
            )
        }

        composeRule.onNodeWithText("2 accounts", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("sign in to those accounts first", substring = true).assertIsDisplayed()
    }

    /** A file with nothing in it still imports, and the sentence does not count zero servers. */
    @Test
    fun `an import of a file naming no server does not count them`() {
        composeRule.setContent {
            Text(
                importedSentence(
                    SettingsImport(
                        serverUrls = emptyList(),
                        exportedAt = null,
                        profilePreferencesApplied = 0,
                        profilePreferencesSkipped = 0,
                    ),
                ),
            )
        }

        composeRule.onNodeWithText("Settings imported.").assertIsDisplayed()
    }

    private fun viewModel() = SettingsTransferViewModel(NoTransfer)

    /**
     * A repository that is never reached: every test above renders, and none of them opens a picker,
     * because a `rememberLauncherForActivityResult` result cannot be produced from a unit test.
     *
     * Throwing rather than returning an empty answer, for the reason `docs/risks.md` R-31 gives about
     * anonymous stand-ins: a fake that quietly succeeds would let a future test believe it had exercised
     * an import it never ran.
     */
    private object NoTransfer : SettingsTransferRepository {
        override suspend fun export(): AppResult<SettingsExport> = error("no test here exports")

        override suspend fun import(document: String): AppResult<SettingsImport> = error("no test here imports")
    }
}
