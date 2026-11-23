package com.example.shelfplayer.feature.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.example.shelfplayer.BuildConfig
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * PRODUCT_SPEC SET-002 / 16.2 — moving between the settings tabs by swiping.
 *
 * ### Why this file exists
 *
 * The gesture arrived with tests for its arithmetic (`SwipeBetweenTest`) and for Home's capsule
 * (`HomeScreenTest`), and none for the settings half — including the one clause the owner asked for by
 * name: *"navigating left in the setting at the left most tab should go back to home screen"*. That is the
 * only place in the app where a swipe leaves the screen it is performed on, so it is the least obvious
 * behaviour here and was the least covered.
 *
 * ### Why the swipe is injected at the root
 *
 * The handler is on the screen's body, which holds the tab row and the list. A horizontal drag starting on
 * the list is not consumed by it — a `LazyColumn` competes for vertical slop only — so it reaches the
 * handler, which is the arrangement the feature relies on and therefore the one worth testing through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Appearance is the tab the screen opens on, so the assertions below have a known starting point.
     *
     * It is first because it is what somebody opens Settings to change. It used to be Server, and the
     * order the owner asked for is Appearance, Playback, Server, About.
     */
    @Test
    fun `the screen opens on the appearance tab`() {
        render()

        tab("Appearance").assertIsSelected()
    }

    /** A swipe towards the left moves to the next tab, the way a pager does. */
    @Test
    fun `swiping left moves to the next tab`() {
        render()

        composeRule.onRoot().performTouchInput { swipeLeft() }

        tab("Playback").assertIsSelected()
        tab("Appearance").assertIsNotSelected()
    }

    /** And towards the right, back again — so the gesture is reversible rather than one-way. */
    @Test
    fun `swiping right moves to the previous tab`() {
        render()
        tab("Server").performClick()

        composeRule.onRoot().performTouchInput { swipeRight() }

        tab("Playback").assertIsSelected()
    }

    /**
     * **The clause the owner asked for: a swipe right on the leftmost tab leaves the screen.**
     *
     * Appearance has nothing to its left inside this screen, and what is behind the screen is the shelf. So
     * `previousOf` answering `null` is not the end of the gesture here — the caller supplies somewhere else
     * to go, which is the one case `swipeBetween`'s nullable parameter exists for.
     */
    @Test
    fun `swiping right on the first tab leaves the screen`() {
        var navigatedUp = 0
        render(onNavigateUp = { navigatedUp += 1 })

        composeRule.onRoot().performTouchInput { swipeRight() }

        assertEquals(1, navigatedUp)
        tab("Appearance").assertIsSelected()
    }

    /**
     * And a swipe left on the **last** tab does nothing at all, which is the asymmetry.
     *
     * There is no wrapping and nothing beyond About, so the gesture is simply ignored. Leaving the screen
     * in this direction would be the opposite of what the gesture means: a listener swiping forward is
     * asking for the next tab, not for the way out.
     */
    @Test
    fun `swiping left on the last tab does nothing`() {
        var navigatedUp = 0
        render(onNavigateUp = { navigatedUp += 1 })
        tab("About").performClick()

        composeRule.onRoot().performTouchInput { swipeLeft() }

        tab("About").assertIsSelected()
        assertEquals(0, navigatedUp)
    }

    /**
     * **The three icon tabs carry an icon and no text; About carries text and no icon.**
     *
     * The assertion the last change lacked. *Appearance* is a long word and four long words left no tab
     * wide enough to read one, so three of them became icons — but nothing verified that, and when the
     * edit silently failed to apply the suite stayed green. Asserting the *absence* of the text is the
     * half that catches it: an icon added while the text stayed would look almost right and be exactly
     * the problem the icons were for.
     */
    @Test
    fun `the three icon tabs are labelled by icon and not by text`() {
        render()

        listOf("Appearance", "Playback", "Server").forEach { label ->
            composeRule.onNode(
                hasContentDescription(label) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
            ).assertExists()
            composeRule.onNode(
                hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
            ).assertDoesNotExist()
        }

        // About keeps its word, on the owner's instruction — it is the one tab whose name is not also the
        // name of a thing on the screen, and no icon means "what this app is" without being guessed at.
        composeRule.onNode(
            hasText("About") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
        ).assertExists()
    }

    /**
     * Every tab is reachable by swiping alone, in order, with no wrapping at either end.
     *
     * One case rather than four, because what is being asserted is the *sequence* — a per-tab test would
     * pass on a gesture that skipped one and landed correctly by accident.
     */
    @Test
    fun `swiping walks the tabs in order and stops at the end`() {
        render()

        listOf("Playback", "Server", "About").forEach { label ->
            composeRule.onRoot().performTouchInput { swipeLeft() }
            tab(label).assertIsSelected()
        }

        composeRule.onRoot().performTouchInput { swipeLeft() }
        tab("About").assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w320dp-h720dp", fontScale = 2.0f)
    fun `about groups diagnostics at narrow width and double text`() = verifyAdvancedDiagnostics()

    @Test
    @Config(qualifiers = "w375dp-h812dp")
    fun `about groups diagnostics at 375 dp`() = verifyAdvancedDiagnostics()

    @Test
    @Config(qualifiers = "w414dp-h896dp")
    fun `about groups diagnostics at 414 dp`() = verifyAdvancedDiagnostics()

    @Test
    @Config(qualifiers = "w768dp-h1024dp")
    fun `about groups diagnostics on a wide screen`() = verifyAdvancedDiagnostics()

    private fun verifyAdvancedDiagnostics() {
        render()
        tab("About").performClick()
        val advanced = "Advanced & diagnostics"
        val list = composeRule.onNode(
            hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        )
        list.performScrollToNode(hasContentDescription(advanced))
        composeRule.onNodeWithText("Playback diagnostics").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(advanced).assertIsDisplayed().performClick()

        val labels = buildList {
            addAll(listOf("Playback diagnostics", "Open the event log", "Open the debug console"))
            if (BuildConfig.DEBUG) addAll(listOf("Android Auto diagnostics", "Expire the access token"))
        }
        for (label in labels) {
            list.performScrollToNode(hasText(label))
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
        if (!BuildConfig.DEBUG) {
            composeRule.onNodeWithText("Android Auto diagnostics").assertDoesNotExist()
            composeRule.onNodeWithText("Expire the access token").assertDoesNotExist()
        }
        list.performScrollToNode(hasContentDescription(advanced))
        composeRule.onNodeWithContentDescription(advanced).performClick()
        composeRule.onNodeWithText("Playback diagnostics").assertDoesNotExist()
    }

    /*
     * The tab in the row, not the section heading that happens to share its wording.
     *
     * "Server" and "Appearance" are each both a tab and a heading on the tab they select, so a text-only
     * matcher finds two nodes and fails before it can assert anything. The role is what separates them.
     */

    /**
     * A tab, found by its **name** whether that name is its text or its icon's description.
     *
     * ### Why this matcher had to change, and what its old form hid
     *
     * It used to match on `hasText` alone. Three of the four tabs are icons now, and an icon's name is a
     * `contentDescription` — which `hasText` does not see. So a `hasText`-only matcher passes **only while
     * the tabs still have text**, and that is exactly what it did: a scripted edit missed the `Tab` block
     * after a merge re-indented it, the icons were never applied, and this suite went green because it was
     * asserting the behaviour that had failed to change. The device found it instead.
     *
     * Matching either form means the suite now says which of the two a tab is using — see
     * `the three icon tabs are labelled by icon and not by text`.
     */
    private fun tab(label: String) = composeRule.onNode(
        (hasText(label) or hasContentDescription(label)) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
    )

    /**
     * The screen with nothing in it, which is all these need.
     *
     * Every input but the four required ones is defaulted on `SettingsScreen` itself — deliberately, so it
     * stays a pure function a test can render without a ViewModel (PRODUCT_SPEC 16.4). These cases are
     * about which tab is selected, and no tab's *content* takes part in that.
     */
    private fun render(onNavigateUp: () -> Unit = {}) {
        composeRule.setContent {
            SettingsScreen(
                uiState = SettingsUiState(),
                serverTab = ServerTabInputs(),
                sleepTimerActions = SleepTimerSettingsActions(
                    onDefaultChanged = {},
                    onFadeChanged = {},
                    onShakeChanged = {},
                    onShakeGraceChanged = {},
                    onShakeSensitivityChanged = {},
                    onRewindOnStopChanged = {},
                ),
                playbackActions = PlaybackSettingsActions(
                    onSpeedChanged = {},
                    onSkipsChanged = {},
                    onAutoRewindChanged = {},
                    onBufferChanged = {},
                ),
                onNavigateUp = onNavigateUp,
            )
        }
    }
}
