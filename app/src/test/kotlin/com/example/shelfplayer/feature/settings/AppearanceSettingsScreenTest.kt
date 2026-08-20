package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.shelfplayer.core.datastore.ThemeMode
import com.example.shelfplayer.core.model.settings.AppLanguage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * PRODUCT_SPEC SET-002 (Appearance/accessibility) — the section that gave two stored settings a control.
 *
 * `theme_mode` and `dynamic_color` were written into the settings proto in the first build and applied by
 * `MainActivity` ever since, and nothing had ever written them: a preference that worked and could not be
 * chosen. These tests are what says it can be chosen now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class AppearanceSettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the three theme choices are offered`() {
        render()

        composeRule.onNodeWithText("System").assertIsDisplayed()
        composeRule.onNodeWithText("Light").assertIsDisplayed()
        composeRule.onNodeWithText("Dark").assertIsDisplayed()
    }

    /**
     * A device that has never written the setting reads proto3's zero value, `THEME_MODE_UNSPECIFIED`.
     *
     * That is not one of the three chips, so a naive `selected == option` leaves the row with nothing
     * selected — which reads as a broken control rather than as a default.
     */
    @Test
    fun `an unwritten setting shows System selected`() {
        render(state = AppearanceUiState(themeMode = ThemeMode.THEME_MODE_UNSPECIFIED))

        composeRule.onNodeWithText("System").assertIsSelected()
    }

    @Test
    fun `choosing dark reports it`() {
        var chosen: ThemeMode? = null
        render(actions = AppearanceActions(onThemeModeChanged = { chosen = it }))

        composeRule.onNodeWithText("Dark").performClick()

        assertEquals(ThemeMode.THEME_MODE_DARK, chosen)
    }

    @Test
    fun `the wallpaper colours are a switch, off by default`() {
        var enabled: Boolean? = null
        render(actions = AppearanceActions(onDynamicColorChanged = { enabled = it }))

        composeRule.onNodeWithText("Use the colours from my wallpaper").performClick()

        assertEquals(true, enabled)
    }

    /**
     * Each language in its own name — the property `AppLanguage.displayName` exists to hold.
     *
     * Asserted on screen and not only on the model, because the failure mode is a `stringResource` call
     * added later "for consistency": the list would then be translated, and a Norwegian speaker looking at
     * an English app would be hunting for a word they cannot read.
     */
    @Test
    fun `languages are listed in their own names`() {
        render()

        scrollTo("Norsk bokmål")
        composeRule.onNodeWithText("Norsk bokmål").assertIsDisplayed()
        composeRule.onNodeWithText("English").assertIsDisplayed()
    }

    @Test
    fun `choosing a language reports it`() {
        var chosen: AppLanguage? = null
        render(actions = AppearanceActions(onLanguageChanged = { chosen = it }))

        scrollTo("Norsk bokmål")
        composeRule.onNodeWithText("Norsk bokmål").performClick()

        assertEquals(AppLanguage.NorwegianBokmal, chosen)
    }

    /**
     * The default, shown as selected — so the row is never blank on a device that has chosen nothing.
     *
     * The two "system" chips on this tab are deliberately worded differently: the theme's says *System* and
     * the language's *System default*, which is what Android's own per-app language picker calls it. They
     * used to share a label, and a test that could not tell them apart is what said so.
     */
    @Test
    fun `the system default is the selected language until one is chosen`() {
        render()

        scrollTo("System default")
        composeRule.onNodeWithText("System default").assertIsSelected()
    }

    private fun scrollTo(text: String) =
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))

    private fun render(
        state: AppearanceUiState = AppearanceUiState(),
        actions: AppearanceActions = AppearanceActions(),
    ) {
        composeRule.setContent {
            LazyColumn {
                appearanceSection(state = state, actions = actions)
            }
        }
    }
}
