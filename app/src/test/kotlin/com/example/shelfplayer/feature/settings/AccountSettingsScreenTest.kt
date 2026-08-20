package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.ServerId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC 5.1 / 5.2 / USER-001 — the section that exists because a hidden row could not be diagnosed.
 *
 * ### The bug this is the regression test for
 *
 * *Manage server accounts* was rendered only for an administrator, and absent for everybody else. On a
 * device that reads as "the feature was never built" — which is what was reported, for an account that was
 * behaving correctly and simply had no administrator grant.
 *
 * So the first two tests are the two halves of the fix: the row is there either way, and when it cannot be
 * used it names the reason. The third is the same idea applied to the grants, which is the other question a
 * missing button raises.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class AccountSettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `an ordinary account still sees the row, with the reason it cannot be opened`() {
        render(profile(role = ProfileRole.Listener))

        scrollTo("Manage server accounts")
        composeRule.onNodeWithText("Manage server accounts").assertIsDisplayed()
        composeRule.onNodeWithText("Only a server administrator can manage accounts.").assertIsDisplayed()
    }

    /** And it does nothing when pressed. The explanation is not a promise. */
    @Test
    fun `an ordinary account cannot open it`() {
        var opened = 0
        render(profile(role = ProfileRole.Listener), onManage = { opened++ })

        scrollTo("Manage server accounts")
        composeRule.onNodeWithText("Manage server accounts").performClick()

        assertEquals(0, opened)
    }

    @Test
    fun `an administrator opens it, with no explanation in the way`() {
        var opened = 0
        render(profile(role = ProfileRole.Admin), onManage = { opened++ })

        scrollTo("Manage server accounts")
        composeRule.onNodeWithText("Only a server administrator can manage accounts.").assertDoesNotExist()
        composeRule.onNodeWithText("Manage server accounts").performClick()

        assertTrue(opened == 1, "an administrator's press did not reach the callback")
    }

    /**
     * PRODUCT_SPEC 5.2 — the four grants, as words.
     *
     * The account here can download and nothing else, which is what the public demo server's `demo` account
     * actually holds. Every management action in the app is gated on one of the other three, so this is the
     * row combination a user is most likely to be looking at when they wonder where the edit button went.
     */
    @Test
    fun `the server's grants are reported, allowed and not`() {
        render(profile(role = ProfileRole.Listener, canDownload = true))

        scrollTo("May delete")
        composeRule.onNodeWithText("May download").assertIsDisplayed()
        composeRule.onNodeWithText("May edit metadata").assertIsDisplayed()
        composeRule.onNodeWithText("May upload covers").assertIsDisplayed()
        composeRule.onNodeWithText("May delete").assertIsDisplayed()
        // One allowed, three not — so the two words are both on screen and cannot be confused.
        composeRule.onNodeWithText("Allowed").assertIsDisplayed()
        assertEquals(3, countOf("Not allowed"), "three grants should be withheld")
    }

    /** The grants are readings. Saying so is what stops somebody looking for the switch that turns one on. */
    @Test
    fun `the hint says the grants belong to the server`() {
        render(profile(role = ProfileRole.Listener))

        scrollTo("Change them on the server")
        composeRule
            .onNodeWithText("not settings this app can change", substring = true)
            .assertIsDisplayed()
    }

    /** Signed out is a state the tab has to survive without implying an account with no permissions. */
    @Test
    fun `no account says so rather than showing four denials`() {
        render(profile = null)

        composeRule.onNodeWithText("No account is signed in on this device.").assertIsDisplayed()
        composeRule.onNodeWithText("May download").assertDoesNotExist()
    }

    /** The username as the server spells it, because that is what an administrator can look up. */
    @Test
    fun `the account is named`() {
        render(profile(role = ProfileRole.Admin, username = "alexander"))

        composeRule.onNodeWithText("alexander").assertIsDisplayed()
        composeRule.onNodeWithText("Administrator").assertIsDisplayed()
    }

    private fun countOf(text: String): Int = composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    private fun scrollTo(text: String) =
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))

    private fun render(profile: Profile?, onManage: () -> Unit = {}) {
        composeRule.setContent {
            LazyColumn {
                accountSection(profile = profile, onManageServerUsers = onManage)
            }
        }
    }

    private fun profile(role: ProfileRole, username: String = "demo", canDownload: Boolean = false) = Profile(
        id = ProfileId("profile-1"),
        serverId = ServerId("server-1"),
        username = username,
        displayName = username,
        role = role,
        requiresReauthentication = false,
        lastUsedAt = null,
        isFixture = false,
        canDownload = canDownload,
    )
}
