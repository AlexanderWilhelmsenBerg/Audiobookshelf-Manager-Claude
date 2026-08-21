package com.example.shelfplayer.a11y

import androidx.compose.ui.test.junit4.createComposeRule
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibraryKind
import com.example.shelfplayer.feature.settings.PlaybackSettingsActions
import com.example.shelfplayer.feature.settings.ServerTabInputs
import com.example.shelfplayer.feature.settings.SettingsScreen
import com.example.shelfplayer.feature.settings.SettingsUiState
import com.example.shelfplayer.feature.settings.SleepTimerSettingsActions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * PRODUCT_SPEC §51 / 2.10 — Settings, which has more controls than any other screen in the app.
 *
 * It is the right screen to hold the first of these tests: four tabs of switches, chips, toggleable rows
 * and navigation rows, several of them hand-rolled rather than Material components. If the two assertions
 * hold here they will hold in most places, and where they do not the failure names the control.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SettingsAccessibilityScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `every control on the server tab announces itself`() {
        render()

        composeRule.assertEveryControlIsLabelled()
    }

    @Test
    fun `every control on the server tab is big enough to hit`() {
        render()

        composeRule.assertEveryControlIsBigEnough()
    }

    private fun render() {
        composeRule.setContent {
            SettingsScreen(
                uiState = SettingsUiState(
                    libraries = listOf(
                        Library(
                            serverId = ServerId("server-1"),
                            id = LibraryId("lib-1"),
                            name = "Fiction",
                            kind = LibraryKind.Book,
                            displayOrder = 0,
                            bookCount = 12,
                            remoteUpdatedAt = null,
                            lastFetchedAt = Instant.EPOCH,
                        ),
                    ),
                    isLoaded = true,
                ),
                serverTab = ServerTabInputs(account = account()),
                // The callbacks are not what these tests are about; the semantics tree is.
                sleepTimerActions = SleepTimerSettingsActions(
                    onDefaultChanged = {},
                    onFadeChanged = {},
                    onShakeChanged = {},
                    onRewindOnStopChanged = {},
                ),
                playbackActions = PlaybackSettingsActions(
                    onSpeedChanged = {},
                    onSkipsChanged = {},
                    onAutoRewindChanged = {},
                    onBufferChanged = {},
                    onAutoPlayChanged = {},
                    onNetworkPolicyChanged = {},
                    onHousekeepingChanged = {},
                    onFocusBehaviourChanged = {},
                    onStartupModeChanged = {},
                    onManageDownloads = {},
                ),
                onNavigateUp = {},
            )
        }
    }

    /** An admin, so the account-management row is rendered rather than explained away. */
    private fun account() = Profile(
        id = ProfileId("profile-1"),
        serverId = ServerId("server-1"),
        username = "alex",
        displayName = "alex",
        role = ProfileRole.Admin,
        requiresReauthentication = false,
        lastUsedAt = Instant.EPOCH,
        isFixture = false,
        canDownload = true,
        canUpdate = true,
        canDelete = true,
        canUpload = true,
    )
}
