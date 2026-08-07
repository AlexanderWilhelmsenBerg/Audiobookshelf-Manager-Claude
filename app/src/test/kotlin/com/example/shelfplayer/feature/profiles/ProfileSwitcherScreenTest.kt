package com.example.shelfplayer.feature.profiles

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC 17.1 (UI tests: profile switching, destructive confirmations) — the switcher, rendered.
 *
 * Two of the cases here are about *wording* rather than behaviour, and they are the reason this file
 * exists rather than only `ProfileSwitcherViewModelTest`. PRODUCT_SPEC 21 requires a destructive action
 * to describe its actual effect, and whether it does is a property of the string on the screen — a state
 * test cannot see it, and a person reading the code once cannot keep seeing it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileSwitcherScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** PRODUCT_SPEC AUTH-002 — server, username and role, per profile. */
    @Test
    fun `each profile shows who and where it is`() {
        compose.setContent {
            ProfileSwitcherScreen(
                uiState = ProfileSwitcherUiState(profiles = listOf(row("ada"), row("kari"))),
                actions = noActions(),
            )
        }

        // Deliberately not "bo": "books.example" contains it, and a substring matcher that hits two
        // nodes fails with an ambiguity error rather than the assertion the test is making.
        compose.onNodeWithText("ada", substring = true).assertExists()
        compose.onNodeWithText("kari", substring = true).assertExists()
        compose.onAllNodesWithText("books.example", substring = true)
            .fetchSemanticsNodes()
            .let { assertEquals(2, it.size, "the server is named on both rows") }
    }

    /** The active profile is marked, so a switch that did nothing is visible as such. */
    @Test
    fun `the profile in use is marked and offers no second switch`() {
        val active = ProfileId("prf_ada")
        compose.setContent {
            ProfileSwitcherScreen(
                uiState = ProfileSwitcherUiState(
                    profiles = listOf(row("ada", id = active), row("kari")),
                    activeProfileId = active,
                ),
                actions = noActions(),
            )
        }

        compose.onNodeWithText("In use").assertExists()
        // One "Use" button, for the profile that is not in use.
        assertEquals(1, compose.onAllNodesWithText("Use").fetchSemanticsNodes().size)
    }

    @Test
    fun `choosing another profile reports which one`() {
        val chosen = mutableListOf<ProfileId>()
        compose.setContent {
            ProfileSwitcherScreen(
                uiState = ProfileSwitcherUiState(
                    profiles = listOf(row("ada", id = ProfileId("prf_ada")), row("kari")),
                    activeProfileId = ProfileId("prf_ada"),
                ),
                actions = noActions().copy(onProfileSelected = { chosen += it }),
            )
        }

        compose.onNodeWithText("Use").performScrollTo().performClick()

        assertEquals(listOf(ProfileId("prf_kari")), chosen)
    }

    /**
     * PRODUCT_SPEC 21 — a destructive action describes its **actual** effect.
     *
     * Three specific claims, because each is a way the wording could go wrong and each would mislead a
     * user differently: that it is this device only, that the server keeps its copy, and that other
     * profiles are untouched. Asserting the sentence rather than the intent is the point — an edit that
     * softened it to "removes this profile" would pass every other test in the repository.
     */
    @Test
    fun `removing a profile says it is local, leaves the server, and spares other profiles`() {
        compose.setContent {
            ProfileSwitcherScreen(
                uiState = ProfileSwitcherUiState(profiles = listOf(row("ada"))),
                actions = noActions(),
            )
        }

        compose.onNodeWithText("Remove").performScrollTo().performClick()
        compose.waitForIdle()

        val body = compose.onNodeWithText("from this device", substring = true)
        body.assertExists()
        compose.onNodeWithText("Nothing is deleted from the server", substring = true).assertExists()
        compose.onNodeWithText("other profiles are not affected", substring = true).assertExists()
    }

    /** Removal is confirmed, never immediate — the dialog is the whole protection. */
    @Test
    fun `removal does not happen until it is confirmed`() {
        val removed = mutableListOf<ProfileId>()
        compose.setContent {
            ProfileSwitcherScreen(
                uiState = ProfileSwitcherUiState(profiles = listOf(row("ada"))),
                actions = noActions().copy(onRemoveProfile = { removed += it }),
            )
        }

        compose.onNodeWithText("Remove").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(removed.isEmpty(), "opening the dialog is not removing")

        compose.onNodeWithText("Remove profile").performClick()
        compose.waitForIdle()

        assertEquals(listOf(ProfileId("prf_ada")), removed)
    }

    /** Cancelling leaves the profile alone, which is the other half of the confirmation being real. */
    @Test
    fun `cancelling a removal removes nothing`() {
        val removed = mutableListOf<ProfileId>()
        compose.setContent {
            ProfileSwitcherScreen(
                uiState = ProfileSwitcherUiState(profiles = listOf(row("ada"))),
                actions = noActions().copy(onRemoveProfile = { removed += it }),
            )
        }

        compose.onNodeWithText("Remove").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        assertTrue(removed.isEmpty())
    }

    /**
     * Sign out and remove are separate, because they are different things.
     *
     * A single "remove" that also signed out — or a sign-out that quietly deleted progress — is the
     * confusion PRODUCT_SPEC 21's wording rule exists to prevent, and the two buttons are how the screen
     * keeps them apart.
     */
    @Test
    fun `signing out is a different button from removing`() {
        val signedOut = mutableListOf<ProfileId>()
        val removed = mutableListOf<ProfileId>()
        compose.setContent {
            ProfileSwitcherScreen(
                uiState = ProfileSwitcherUiState(profiles = listOf(row("ada"))),
                actions = noActions().copy(onSignOut = { signedOut += it }, onRemoveProfile = { removed += it }),
            )
        }

        compose.onNodeWithText("Sign out").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf(ProfileId("prf_ada")), signedOut)
        assertTrue(removed.isEmpty(), "signing out is not removing")
    }

    private fun row(username: String, id: ProfileId = ProfileId("prf_$username")) = ProfileRow(
        profile = Profile(
            id = id,
            serverId = ServerId("srv_books"),
            username = username,
            displayName = username,
            role = ProfileRole.Listener,
            requiresReauthentication = false,
            lastUsedAt = Instant.EPOCH,
            isFixture = false,
        ),
        server = Server(
            id = ServerId("srv_books"),
            displayName = "books.example",
            baseUrl = "https://books.example",
            detectedVersion = "2.36.0",
            isFixture = false,
        ),
    )

    private fun noActions() = ProfileSwitcherActions(
        onProfileSelected = {},
        onSignOut = {},
        onSignInAgain = { _, _ -> },
        onRemoveProfile = {},
        onErrorDismissed = {},
        onNavigateUp = {},
        onAddProfile = {},
    )
}
