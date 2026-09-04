package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.example.shelfplayer.core.model.settings.AccentColor
import com.example.shelfplayer.core.model.settings.AppLanguage
import com.example.shelfplayer.core.model.settings.AppTheme
import com.example.shelfplayer.core.model.settings.BackgroundTheme
import com.example.shelfplayer.core.model.settings.GlassBlur
import com.example.shelfplayer.core.model.settings.GlassTint
import com.example.shelfplayer.core.model.settings.TextContrast
import com.example.shelfplayer.core.model.settings.ThemeAccents
import com.example.shelfplayer.core.model.settings.ThemeGlass
import com.example.shelfplayer.core.model.settings.ThemeGround
import com.example.shelfplayer.core.model.settings.ThemeSurfaces
import com.example.shelfplayer.core.model.settings.ThemeText
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

    /**
     * The slider the owner asked for, and the sentinel underneath it.
     *
     * Zero dp is a real choice — *wash only* — which is why `GlassBlur` stores it as -1: proto3 cannot
     * tell a stored zero from a field nobody wrote, so a plain zero would read back as the default and
     * silently turn the blur on again. The label has to say *Off* rather than "0 dp" for the same reason
     * it is a choice at all.
     */
    @Test
    fun `the blur slider reports a radius and says when it is off`() {
        var dp: Int? = null
        render(
            state = AppearanceUiState(glassBlurDp = 0),
            actions = AppearanceActions(onGlassBlurChanged = { dp = it }),
        )

        scrollTo("Blur")
        composeRule.onNodeWithText("Off").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Blur").performSemanticsAction(SemanticsActions.SetProgress) {
            it(GlassBlur.MAX_DP.toFloat())
        }
        assertEquals(GlassBlur.MAX_DP, dp)
    }

    /** The radius, shown, so the slider is not a mystery gesture. */
    @Test
    fun `a chosen radius is shown in dp`() {
        render(state = AppearanceUiState(glassBlurDp = GlassBlur.DEFAULT_DP))

        scrollTo("Blur")
        composeRule.onNodeWithText("${GlassBlur.DEFAULT_DP} dp").assertIsDisplayed()
    }

    /**
     * **The text contrast, which exists because of a bug and is deliberately not a colour picker.**
     *
     * A transparent `Scaffold` container had defaulted every word on the screen to black, which on the
     * AMOLED theme is black on black. Offering *black* as a choice would put that state back within one
     * tap; a level cannot, because every level is measured from the ground it is read against. See
     * `TextContrast` and `GlassContentColorScreenTest`.
     */
    @Test
    fun `text contrast is offered as levels and reports the chosen one`() {
        var chosen: TextContrast? = null
        render(actions = AppearanceActions(onTextContrastChanged = { chosen = it }))

        scrollTo("Text contrast")
        composeRule.onNodeWithText("Automatic").assertIsDisplayed()
        composeRule.onNodeWithText("Soft").assertIsDisplayed()
        composeRule.onNodeWithText("High").performClick()

        assertEquals(TextContrast.High, chosen)
    }

    /**
     * **The picker shows the themes, and every cell is named.**
     *
     * A row of pictures with no `contentDescription` is six unlabelled controls to a screen reader, and
     * the label is the only thing that distinguishes them — the artwork cannot be described. *None* is
     * asserted alongside them because a picker whose only exits are pictures is one you cannot leave.
     */
    @Test
    fun `the background themes are offered as named thumbnails, with a way back to none`() {
        render(state = AppearanceUiState(backgroundThemes = themes()))

        composeRule.onNodeWithContentDescription("None").assertIsDisplayed()
        themes().forEach { theme ->
            composeRule.onNodeWithContentDescription(theme.name).assertIsDisplayed()
        }
    }

    @Test
    fun `choosing a background theme reports its id, and none reports null`() {
        var chosen: String? = "unset"
        render(
            state = AppearanceUiState(backgroundThemes = themes(), backgroundThemeId = null),
            actions = AppearanceActions(onBackgroundThemeChanged = { chosen = it }),
        )

        composeRule.onNodeWithContentDescription("Teal Horizon").performClick()
        assertEquals("teal_horizon", chosen)

        composeRule.onNodeWithContentDescription("None").performClick()
        assertEquals(null, chosen)
    }

    /**
     * With no packs on disk the group is absent rather than empty.
     *
     * An empty picker is a heading over nothing, which reads as a broken screen. The catalog returns an
     * empty list when the assets are unreadable — a build fault — and the honest thing on that path is to
     * show the rest of the tab, not a hole.
     */
    @Test
    fun `no bundled themes means no picker at all`() {
        render(state = AppearanceUiState(backgroundThemes = emptyList()))

        composeRule.onNodeWithText("Background theme").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("None").assertDoesNotExist()
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

    /** Two packs' worth of shape, which is all the picker reads. Colours are irrelevant to these cases. */
    private fun themes() = listOf(
        backgroundTheme(id = "teal_horizon", name = "Teal Horizon"),
        backgroundTheme(id = "nebula_glow", name = "Nebula Glow"),
    )

    private fun backgroundTheme(id: String, name: String) = BackgroundTheme(
        id = id,
        name = name,
        pack = "Test Pack",
        isDark = true,
        ground = ThemeGround(asset = "themes/$id/background.webp", base = 0xFF000000, scrim = 0x80000000),
        surfaces = ThemeSurfaces(
            card = 0xCC101010,
            cardElevated = 0xDD181818,
            navigation = 0xD9101010,
            divider = 0x33FFFFFF,
        ),
        glass = ThemeGlass(tint = 0x33FFFFFF, border = 0x59FFFFFF, blurDp = 24),
        accents = ThemeAccents(
            primary = 0xFF9EF4EA,
            primaryContainer = 0xFF24555E,
            onPrimary = 0xFF0A2327,
            secondary = 0xFFFFE1A6,
            tertiary = 0xFFA2DCFF,
            error = 0xFFFF9A9A,
        ),
        text = ThemeText(primary = 0xFFF6FFFF, secondary = 0xFFE0F2F1, muted = 0xFFB7D0D0, inverse = 0xFF0B232A),
    )

    /**
     * Scrolls the tab, and only the tab.
     *
     * `hasScrollAction()` alone stopped being unique the moment the tab gained horizontal rows — the
     * accent swatches and the theme thumbnails both scroll, so the matcher found three nodes and refused.
     * Keying on the **vertical** axis range names the one that is the page.
     */
    private fun scrollTo(text: String) = composeRule
        .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        .performScrollToNode(hasText(text, substring = true))

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
