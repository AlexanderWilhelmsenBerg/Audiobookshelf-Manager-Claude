package com.example.shelfplayer.feature.onboarding

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.ServerCandidate
import com.example.shelfplayer.core.model.ServerProbe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC 17.1 (UI tests: login) — the sign-in screen, rendered.
 *
 * `SignInViewModelTest` already covers the state machine. This covers the half a ViewModel test cannot:
 * what is actually on the screen, and whether the thing the requirement promises is reachable by a
 * finger. The two failures it is written against are both ones a state test would pass through —
 * a password field rendered during the address stage, and a cleartext warning computed but never drawn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignInScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * PRODUCT_SPEC 6.1 — the password field does not exist until the address is confirmed.
     *
     * Asserted on the rendered tree rather than on the stage enum, because "the state says Address" and
     * "there is nowhere to type a password" are different claims, and it is the second one AUTH-001
     * makes.
     */
    @Test
    fun `the address stage offers nowhere to type a password`() {
        compose.setContent { SignInScreen(uiState = SignInUiState(), actions = noActions()) }

        compose.onNodeWithText("Server address").assertExists()
        compose.onNodeWithText("Password").assertDoesNotExist()
        compose.onNodeWithText("Sign in").assertDoesNotExist()
    }

    /** A blank address cannot be submitted, so the button says so rather than failing on tap. */
    @Test
    fun `continue is disabled until an address is typed`() {
        compose.setContent { SignInScreen(uiState = SignInUiState(), actions = noActions()) }

        compose.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun `typing an address reaches the caller`() {
        val typed = mutableListOf<String>()
        compose.setContent {
            SignInScreen(
                uiState = SignInUiState(),
                actions = noActions().copy(onServerUrlChanged = { typed += it }),
            )
        }

        compose.onNodeWithText("Server address").performTextInput("books.example")

        assertTrue(typed.isNotEmpty(), "the field is not read-only")
    }

    /**
     * PRODUCT_SPEC 15 — a user about to type a password into an `http://` address is told it will cross
     * the network unencrypted.
     *
     * The warning is the reason the address stage exists at all, and it is the kind of string that gets
     * computed correctly and then left out of a layout. This renders the screen and looks for it.
     */
    @Test
    fun `an http address warns that the password would not be encrypted`() {
        compose.setContent {
            SignInScreen(
                uiState = SignInUiState(
                    stage = SignInStage.Credentials,
                    candidate = candidate(url = "http://books.example"),
                ),
                actions = noActions(),
            )
        }

        compose.onNodeWithText("not encrypted", substring = true).assertExists()
    }

    @Test
    fun `an https address does not warn`() {
        compose.setContent {
            SignInScreen(
                uiState = SignInUiState(
                    stage = SignInStage.Credentials,
                    candidate = candidate(url = "https://books.example"),
                ),
                actions = noActions(),
            )
        }

        compose.onNodeWithText("not encrypted", substring = true).assertDoesNotExist()
    }

    /**
     * PRODUCT_SPEC 14.4 — an error is shown in plain language, on the screen, not only in the state.
     */
    @Test
    fun `a failure is rendered where the user is looking`() {
        val summary = "That server could not be reached."
        compose.setContent {
            SignInScreen(
                uiState = SignInUiState(error = AppError.Network(summary = summary)),
                actions = noActions(),
            )
        }

        compose.onNodeWithText(summary, substring = true).assertExists()
    }

    /**
     * The credentials stage can be left, so a mistyped host is not a dead end.
     *
     * `performScrollTo` before the click, and not incidentally: on the small screen this test renders at,
     * the control sits below the fold. Reaching it by scrolling is what a user does, and asserting it
     * that way is what makes the test true on a phone rather than only on a tall emulator.
     */
    @Test
    fun `the credentials stage offers a way back to the address`() {
        var backs = 0
        compose.setContent {
            SignInScreen(
                uiState = SignInUiState(stage = SignInStage.Credentials, candidate = candidate()),
                actions = noActions().copy(onBackToServer = { backs++ }),
            )
        }

        compose.onNodeWithText("Use a different server").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(1, backs)
    }

    private fun candidate(url: String = "https://books.example") = ServerCandidate(
        serverUrl = url,
        isCleartext = url.startsWith("http://"),
        wasSchemeAssumed = false,
        probe = ServerProbe(
            isAudiobookshelf = true,
            serverVersion = "2.36.0",
            isInitialized = true,
            authMethods = listOf("local"),
        ),
    )

    /**
     * Every callback a no-op, so a test names only the one it cares about.
     *
     * `SignInActions` is a data class, so `copy` is how a test overrides one without restating six.
     */
    private fun noActions() = SignInActions(
        onServerUrlChanged = {},
        onServerSubmitted = {},
        onKnownServerSelected = {},
        onBackToServer = {},
        onUsernameChanged = {},
        onPasswordChanged = {},
        onCredentialsSubmitted = {},
    )
}
