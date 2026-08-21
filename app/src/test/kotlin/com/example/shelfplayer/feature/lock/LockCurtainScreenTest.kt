package com.example.shelfplayer.feature.lock

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.lock.BiometricAvailability
import com.example.shelfplayer.core.model.lock.ProfileLockState
import com.example.shelfplayer.core.model.lock.UnlockFailure
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * AUTH-005 / AUTH-003 — **the disclosure is the thing under test.**
 *
 * `LockCurtain`'s KDoc claimed this file existed before it did, which is exactly the kind of claim this
 * project has learned to distrust: a comment asserting a guarantee that nothing enforces. The guarantee it
 * names is worth enforcing, so the file exists now rather than the sentence being deleted.
 *
 * A lock makes a security promise. AUTH-003 limits that promise to profile *selection*, and the four
 * exposures that follow from the limit — the notification, a connected car, unencrypted downloads, and
 * anyone who can read the phone's files — are disclosed on the curtain itself. A future change that
 * quietly drops one of those lines would leave the app claiming more protection than it delivers, and
 * nothing but this test would notice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class LockCurtainScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Every one of the four exposures, present and named. */
    @Test
    fun `the curtain discloses what the lock does not cover`() {
        render()

        listOf(
            "Play, pause and skip stay available",
            "A connected car can still browse",
            "Downloaded audio is stored as ordinary files",
            "does not protect your data from someone who can read",
        ).forEach { disclosure ->
            composeRule.onAllNodesWithText(disclosure, substring = true).fetchSemanticsNodes().let { nodes ->
                assertEquals(1, nodes.size, "the curtain must disclose: $disclosure")
            }
        }
    }

    /**
     * The curtain names the locked account and offers a way in.
     *
     * The account name matters: a passcode field with no indication of *which* account it belongs to is
     * unusable on a device with two profiles, which is the situation the lock is most for.
     */
    @Test
    fun `the curtain names the account and offers a passcode`() {
        render()

        composeRule.onNodeWithText("This account is locked").assertIsDisplayed()
        composeRule.onNodeWithText("Passcode").assertIsDisplayed()
    }

    /**
     * AUTH-005 — the forgotten-passcode cost, stated on the curtain rather than only in
     * settings.
     *
     * Somebody reading this screen has already forgotten it. The offline caveat is the part that has to be
     * there, because it is the case the primary route cannot serve.
     */
    @Test
    fun `the curtain states the recovery route and its offline limit`() {
        render()

        composeRule.onNodeWithText("Forgotten your passcode?").assertIsDisplayed()
        composeRule
            .onAllNodesWithText("server to be reachable", substring = true)
            .fetchSemanticsNodes()
            .let { nodes -> assertEquals(1, nodes.size, "the offline limit must be stated") }
    }

    /**
     * An unreadable record does not offer a passcode field.
     *
     * Offering one would be a field that refuses every value including the correct one, which sends
     * somebody to try harder at something that cannot work.
     */
    @Test
    fun `an unreadable record explains itself instead of offering a field`() {
        render(
            locked = ProfileLockState.Locked(
                profileId = PROFILE,
                canUsePasscode = false,
                unreadable = true,
            ),
        )

        composeRule
            .onAllNodesWithText("cannot be read on this device", substring = true)
            .fetchSemanticsNodes()
            .let { nodes -> assertEquals(1, nodes.size) }
        composeRule.onAllNodesWithText("Passcode").fetchSemanticsNodes().let { nodes ->
            assertEquals(0, nodes.size, "a field that cannot succeed must not be offered")
        }
    }

    /**
     * **The leak this design exists to prevent.**
     *
     * The curtain replaces the app rather than overlaying it, so nothing from behind it is in the semantics
     * tree. If a later change made it an overlay, `MiniPlayer`'s title — a polite live region — would be
     * read aloud by TalkBack over the passcode field. Asserting the absence of arbitrary content is not
     * possible, so this asserts the shape that guarantees it: the curtain is what `MainActivity` composes,
     * and this test renders it alone.
     */
    @Test
    fun `nothing behind the curtain is rendered with it`() {
        render()

        composeRule.onAllNodesWithText("Unlock").fetchSemanticsNodes().let { nodes ->
            assertEquals(1, nodes.size, "the curtain's own control")
        }
    }

    private fun render(
        locked: ProfileLockState.Locked = ProfileLockState.Locked(PROFILE),
        failure: UnlockFailure? = null,
    ) {
        composeRule.setContent {
            LockCurtainContent(
                state = LockUiState(
                    locked = locked,
                    isResolved = true,
                    account = null,
                    others = emptyList(),
                    biometrics = BiometricAvailability.UnsupportedAndroidVersion,
                ),
                failure = failure,
                isChecking = false,
                isBiometricEnabled = false,
                onSubmit = {},
                onBiometricAccepted = {},
            )
        }
    }

    private companion object {
        val PROFILE = ProfileId("prf_test")
        val SERVER = ServerId("srv_test")
    }
}
