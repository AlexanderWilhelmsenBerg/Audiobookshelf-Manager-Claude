package com.example.shelfplayer.feature.onboarding

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.graphics.createBitmap
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.shelfplayer.navigation.signInBackAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AUTH-001/004, section 16.2/17.2/21, #176: actual screen callback through a real navigation stack. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w375dp-h1280dp")
class SignInNavigationScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var nav: NavHostController
    private var dispatcher: OnBackPressedDispatcher? = null
    private var wizardBacks = 0
    private lateinit var renderedView: View

    @Test
    fun `root onboarding has no route Back arrow`() {
        render(pushed = false)
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNodeWithText("Server address").assertIsDisplayed()
    }

    @Test
    fun `pushed address toolbar Back returns to Profiles`() {
        render(pushed = true)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Profiles origin").assertIsDisplayed()
        assertEquals(0, wizardBacks)
    }

    @Test
    fun `pushed credential toolbar Back returns without changing wizard state`() {
        render(pushed = true, credentials = true)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Profiles origin").assertIsDisplayed()
        assertEquals(0, wizardBacks)
    }

    @Test
    fun `system Back and toolbar Back share the pushed destination`() {
        render(pushed = true)
        compose.runOnIdle { requireNotNull(dispatcher).onBackPressed() }
        compose.onNodeWithText("Profiles origin").assertIsDisplayed()
        assertEquals(0, wizardBacks)
    }

    @Test
    fun `wizard Change Server stays inside Sign in`() {
        render(pushed = true, credentials = true)
        compose.onNodeWithText("Use a different server").performScrollTo().performClick()
        assertEquals(1, wizardBacks)
        compose.runOnIdle { assertEquals("sign-in", nav.currentDestination?.route) }
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w320dp-h1280dp", fontScale = 2.0f)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `large text pushed Back remains labelled with a forty eight dp target`() {
        render(pushed = true)
        val back = compose.onNodeWithContentDescription("Back")
        back.assertIsDisplayed()
        val bounds = back.getBoundsInRoot()
        assertTrue((bounds.right - bounds.left).value >= 48f, "Back width: ${(bounds.right - bounds.left).value} dp")
        assertTrue((bounds.bottom - bounds.top).value >= 48f, "Back height: ${(bounds.bottom - bounds.top).value} dp")
        val title = compose.onNodeWithText("Connect a server")
        title.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow, "Toolbar title must render every line")
        val titleBounds = title.getBoundsInRoot()
        val requiredHeight = layouts.single().size.height / renderedView.resources.displayMetrics.density
        assertTrue(
            (titleBounds.bottom - titleBounds.top).value >= requiredHeight - 1f,
            "Full title must fit the toolbar",
        )
        capture()
    }

    private fun render(pushed: Boolean, credentials: Boolean = false) {
        compose.setContent {
            renderedView = LocalView.current
            nav = rememberNavController()
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            NavHost(navController = nav, startDestination = if (pushed) "profiles" else "sign-in") {
                composable("profiles") { Text("Profiles origin") }
                composable("sign-in") {
                    SignInScreen(
                        uiState = SignInUiState(
                            stage = if (credentials) SignInStage.Credentials else SignInStage.Address,
                        ),
                        actions = actions(),
                        onNavigateUp = nav.signInBackAction(),
                    )
                }
            }
            LaunchedEffect(Unit) { if (pushed) nav.navigate("sign-in") }
        }
        compose.waitForIdle()
    }

    private fun capture() {
        lateinit var image: Bitmap
        compose.runOnIdle {
            image = createBitmap(renderedView.width, renderedView.height, Bitmap.Config.ARGB_8888)
            renderedView.draw(Canvas(image))
        }
        val directory = File("build/sign-in-screen-evidence").apply { mkdirs() }
        File(directory, "sign-in-pushed-320dp-font2.png").outputStream().use {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun actions() = SignInActions(
        onServerUrlChanged = {},
        onServerSubmitted = {},
        onKnownServerSelected = {},
        onBackToServer = { wizardBacks++ },
        onUsernameChanged = {},
        onPasswordChanged = {},
        onCredentialsSubmitted = {},
    )
}
