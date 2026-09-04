package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.shelfplayer.core.model.settings.AccentColor
import com.example.shelfplayer.core.model.settings.AppLanguage
import com.example.shelfplayer.core.model.settings.AppTheme
import com.example.shelfplayer.core.model.settings.GlassTint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * PRODUCT_SPEC SET-002 (Appearance/accessibility) — the tab that owns how the app looks.
 *
 * `theme_mode` and `dynamic_color` were written into the settings proto in the first build and applied by
 * `MainActivity` ever since, and nothing had ever written them: a preference that worked and could not be
 * chosen. These tests are what says it can be chosen — and now that the section has become a tab, that the
 * theme, the accent, the glass tint and its two switches can be too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class AppearanceSettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the four theme choices are offered`() {
        render()

        composeRule.onNodeWithText("System").assertIsDisplayed()
        composeRule.onNodeWithText("Light").assertIsDisplayed()
        composeRule.onNodeWithText("Dark").assertIsDisplayed()
        composeRule.onNodeWithText("AMOLED").assertIsDisplayed()
    }

    /**
     * A device that has never chosen shows *System* selected, so the row is never blank.
     *
     * `AppTheme.Default` is that answer, and it is the state's default — the reconciliation of the two
     * stored fields happens in the view model, which is where `AppearanceViewModelTest` asserts it.
     */
    @Test
    fun `an unwritten setting shows System selected`() {
        render()

        composeRule.onNodeWithText("System").assertIsSelected()
    }

    @Test
    fun `choosing dark reports it`() {
        var chosen: AppTheme? = null
        render(actions = AppearanceActions(onThemeChanged = { chosen = it }))

        composeRule.onNodeWithText("Dark").performClick()

        assertEquals(AppTheme.Dark, chosen)
    }

    /** The theme the whole change was asked for, and the one that carries its own explanation. */
    @Test
    fun `choosing AMOLED reports it and says what it does`() {
        var chosen: AppTheme? = null
        render(
            state = AppearanceUiState(theme = AppTheme.Amoled, isDark = true),
            actions = AppearanceActions(onThemeChanged = { chosen = it }),
        )

        composeRule.onNodeWithText("AMOLED").assertIsSelected()
        composeRule.onNodeWithText("Dark with true black surfaces.", substring = true).assertIsDisplayed()

        composeRule.onNodeWithText("AMOLED").performClick()
        assertEquals(AppTheme.Amoled, chosen)
    }

    /**
     * **Each swatch is named.** A row of coloured circles with no `contentDescription` is unusable to a
     * screen reader and to anyone who cannot tell the colours apart — which is a group with an unusually
     * strong reason to be on an appearance screen in the first place.
     */
    @Test
    fun `every accent swatch carries its name`() {
        render()

        AccentColor.entries.forEach { accent ->
            composeRule.onNodeWithContentDescription(accent.name).assertIsDisplayed()
        }
    }

    @Test
    fun `choosing an accent reports it`() {
        var chosen: AccentColor? = null
        render(actions = AppearanceActions(onAccentChanged = { chosen = it }))

        composeRule.onNodeWithContentDescription("Plum").performClick()

        assertEquals(AccentColor.Plum, chosen)
    }

    @Test
    fun `choosing a tint colour reports it`() {
        var chosen: GlassTint? = null
        render(actions = AppearanceActions(onGlassTintChanged = { chosen = it }))

        // Scrolled to by the heading above it: a swatch's name is a `contentDescription`, and
        // `hasText` does not see one — which is the whole reason the swatches need the description.
        scrollTo("Tint colour")
        composeRule.onNodeWithContentDescription("Warm").performClick()

        assertEquals(GlassTint.Warm, chosen)
    }

    /** The two switches the owner asked for by name, and they are genuinely two. */
    @Test
    fun `the card and system tints are separate switches`() {
        var card: Boolean? = null
        var system: Boolean? = null
        render(
            actions = AppearanceActions(
                onCardGlassTintChanged = { card = it },
                onSystemGlassTintChanged = { system = it },
            ),
        )

        scrollTo("Tint the cards")
        composeRule.onNodeWithText("Tint the cards").performClick()
        assertEquals(false, card)
        assertEquals(null, system)

        scrollTo("Tint the bars and the player")
        composeRule.onNodeWithText("Tint the bars and the player").performClick()
        assertEquals(false, system)
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
                appearanceTab(state = state, actions = actions)
            }
        }
    }
}
