package com.example.shelfplayer.a11y

import androidx.compose.ui.test.junit4.createComposeRule
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.feature.downloads.DownloadRow
import com.example.shelfplayer.feature.downloads.DownloadsScreen
import com.example.shelfplayer.feature.downloads.DownloadsUiState
import com.example.shelfplayer.feature.profiles.ProfileRow
import com.example.shelfplayer.feature.profiles.ProfileSwitcherActions
import com.example.shelfplayer.feature.profiles.ProfileSwitcherScreen
import com.example.shelfplayer.feature.profiles.ProfileSwitcherUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * PRODUCT_SPEC §51 / 2.10 — the same net over the two remaining list screens.
 *
 * The downloads queue and the profile switcher are here together because they share the property that
 * makes them worth checking: both are lists of **hand-rolled rows** rather than Material list items, each
 * carrying two or three controls of its own — remove, pin, sign out, sign in again. Material's components
 * are correct by construction; rows like these are where a 32dp icon ends up being the only way to delete
 * something.
 *
 * Between these and the Settings and book-screen tests, every screen with a destructive action is covered.
 * The player and the shelf are not, and `docs/risks.md` R-29 says so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ScreenAccessibilityScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `every control in the downloads queue announces itself`() {
        renderDownloads()

        composeRule.assertEveryControlIsLabelled()
    }

    @Test
    fun `every control in the downloads queue is big enough to hit`() {
        renderDownloads()

        composeRule.assertEveryControlIsBigEnough()
    }

    /**
     * A row whose book the active profile may not see is still shown and still deletable.
     *
     * Included because a `null` title is where a row's label comes from somewhere other than the obvious
     * field, and an unlabelled delete button on a book the user cannot even name is the worst version of
     * this defect.
     */
    @Test
    fun `a row for an invisible book still announces its controls`() {
        renderDownloads(title = null, author = null)

        composeRule.assertEveryControlIsLabelled()
    }

    @Test
    fun `every control in the profile switcher announces itself`() {
        renderProfiles()

        composeRule.assertEveryControlIsLabelled()
    }

    @Test
    fun `every control in the profile switcher is big enough to hit`() {
        renderProfiles()

        composeRule.assertEveryControlIsBigEnough()
    }

    /** A profile that has to sign in again grows a control the ordinary row does not have. */
    @Test
    fun `the reauthentication control announces itself`() {
        renderProfiles(requiresReauthentication = true)

        composeRule.assertEveryControlIsLabelled()
    }

    private fun renderDownloads(title: String? = "The Salt Harbour", author: String? = "A. Writer") {
        composeRule.setContent {
            DownloadsScreen(
                uiState = DownloadsUiState(
                    books = listOf(
                        DownloadRow(
                            bookId = LibraryItemId("book-1"),
                            serverId = ServerId("server-1"),
                            title = title,
                            author = author,
                            fileCount = 12,
                            bytes = 400_000_000,
                            isComplete = true,
                            isFailed = false,
                            isPinned = false,
                            isSharedWithAnotherProfile = false,
                        ),
                    ),
                    totalBytes = 400_000_000,
                    isLoaded = true,
                ),
                onRemove = { _, _ -> },
                onPinnedChanged = { _, _, _ -> },
                onPauseToggled = { _, _ -> },
                onVerify = {},
                onNavigateUp = {},
            )
        }
    }

    private fun renderProfiles(requiresReauthentication: Boolean = false) {
        composeRule.setContent {
            ProfileSwitcherScreen(
                uiState = ProfileSwitcherUiState(
                    profiles = listOf(
                        ProfileRow(
                            profile = profile(requiresReauthentication),
                            server = Server(
                                id = ServerId("server-1"),
                                displayName = "books.example",
                                baseUrl = "https://books.example",
                                detectedVersion = "2.36.0",
                                isFixture = false,
                            ),
                        ),
                    ),
                    activeProfileId = ProfileId("profile-1"),
                ),
                actions = ProfileSwitcherActions(
                    onProfileSelected = {},
                    onSignOut = {},
                    onSignInAgain = { _, _ -> },
                    onRemoveProfile = {},
                    onErrorDismissed = {},
                    onNavigateUp = {},
                    onAddProfile = {},
                ),
            )
        }
    }

    private fun profile(requiresReauthentication: Boolean) = Profile(
        id = ProfileId("profile-1"),
        serverId = ServerId("server-1"),
        username = "ada",
        displayName = "ada",
        role = ProfileRole.Listener,
        requiresReauthentication = requiresReauthentication,
        lastUsedAt = Instant.EPOCH,
        isFixture = false,
        canDownload = true,
    )
}
