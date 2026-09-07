package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.example.shelfplayer.core.model.settings.AccentColor
import com.example.shelfplayer.core.model.settings.AccentScheme
import com.example.shelfplayer.core.model.settings.AppLanguage
import com.example.shelfplayer.core.model.settings.AppTheme
import com.example.shelfplayer.core.model.settings.BackgroundTheme
import com.example.shelfplayer.core.model.settings.GlassBlur
import com.example.shelfplayer.core.model.settings.GlassTint
import com.example.shelfplayer.core.model.settings.TextContrast
import com.example.shelfplayer.core.model.settings.ThemeAccents
import com.example.shelfplayer.core.model.settings.ThemeChoice
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

/** PRODUCT_SPEC SET-002 — the Appearance tab's visible and accessibility behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class AppearanceSettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `every look is in one list, the app's own and the packs alike`() {
        render(state = AppearanceUiState(backgroundThemes = themes()))

        open("Theme, System")

        composeRule.onNodeWithText("System").assertIsDisplayed()
        composeRule.onNodeWithText("Light").assertIsDisplayed()
        composeRule.onNodeWithText("Dark").assertIsDisplayed()
        composeRule.onNodeWithText("AMOLED").assertIsDisplayed()
        themes().forEach { theme ->
            composeRule.onNodeWithText(theme.name).assertIsDisplayed()
        }
    }

    @Test
    fun `an unwritten setting shows System`() {
        render()

        composeRule.onNodeWithContentDescription("Theme, System").assertIsDisplayed()
    }

    @Test
    fun `the theme row does not repeat its own heading`() {
        render()

        composeRule.onAllNodesWithText("Theme").assertCountEquals(1)

        scrollToDescription("Language, System default")
        composeRule.onAllNodesWithText("Language").assertCountEquals(1)
    }

    @Test
    fun `choosing dark reports it`() {
        var chosen: ThemeChoice? = null
        render(actions = AppearanceActions(onThemeChoiceChanged = { chosen = it }))

        open("Theme, System")
        composeRule.onNodeWithText("Dark").performClick()

        assertEquals(ThemeChoice.Plain(AppTheme.Dark), chosen)
    }

    @Test
    fun `choosing AMOLED reports it`() {
        var chosen: ThemeChoice? = null
        render(
            state = AppearanceUiState(theme = AppTheme.Amoled, isDark = true),
            actions = AppearanceActions(onThemeChoiceChanged = { chosen = it }),
        )

        open("Theme, AMOLED")
        composeRule.onNodeWithText("AMOLED").performClick()

        assertEquals(ThemeChoice.Plain(AppTheme.Amoled), chosen)
    }

    /** Appearance keeps labels and values, but no explanatory paragraphs. */
    @Test
    fun `no group explains itself`() {
        render(state = AppearanceUiState(backgroundThemes = themes()))

        listOf(
            "Dark with true black surfaces",
            "The background scrolls with you",
            "light or dark setting, including any schedule",
            "The wash over the blurred surfaces",
            "there is no blur available",
            "Available on Android 12 and later",
            "written in its own name",
            "Used for buttons, switches and anything selected",
            "How far the frosted surfaces smear",
            "How strongly text stands off its background",
            "shelves that sit directly on the background",
            "The navigation bar, the top bars and the mini player",
        ).forEach { sentence ->
            composeRule.onAllNodesWithText(sentence, substring = true).assertCountEquals(0)
        }
    }

    @Test
    fun `a chosen pack is what the theme row shows`() {
        render(state = AppearanceUiState(backgroundThemes = themes(), backgroundThemeId = "teal_horizon"))

        composeRule.onNodeWithContentDescription("Theme, Teal Horizon").assertIsDisplayed()
    }

    /** The palette is visual, but every dot remains named for TalkBack. */
    @Test
    fun `accent names are semantic only inside the swatch grid`() {
        render()

        open("Accent colour, Teal")

        AccentColor.entries.forEach { accent ->
            composeRule.onNodeWithContentDescription(accent.name).assertIsDisplayed()
            composeRule.onAllNodesWithText(accent.name).assertCountEquals(0)
        }
    }

    @Test
    fun `the collapsed row announces the setting and its value together`() {
        render()

        composeRule.onNodeWithContentDescription("Theme, System").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Accent colour, Teal").assertIsDisplayed()

        scrollToDescription("Language, System default")
        composeRule.onNodeWithContentDescription("Language, System default").assertIsDisplayed()
    }

    @Test
    fun `choosing an accent swatch reports it`() {
        var chosen: AccentScheme? = null
        render(actions = AppearanceActions(onAccentChanged = { chosen = it }))

        open("Accent colour, Teal")
        composeRule.onNodeWithContentDescription("Plum").performClick()

        assertEquals(AccentScheme.of(AccentColor.Plum), chosen)
    }

    @Test
    fun `a background pack's own scheme is offered among the accent swatches`() {
        var chosen: AccentScheme? = null
        render(
            state = AppearanceUiState(backgroundThemes = themes()),
            actions = AppearanceActions(onAccentChanged = { chosen = it }),
        )

        open("Accent colour, Teal")
        composeRule.onNodeWithContentDescription("Nebula Glow").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Teal Horizon").performClick()

        assertEquals(AccentScheme.of(themes().first()), chosen)
    }

    @Test
    fun `a chosen pack accent shows the pack's name in semantics`() {
        render(
            state = AppearanceUiState(
                backgroundThemes = themes(),
                accent = AccentScheme.of(themes().first()),
            ),
        )

        composeRule.onNodeWithContentDescription("Accent colour, Teal Horizon").assertIsDisplayed()
    }

    @Test
    fun `wallpaper colour is the bottom accent choice and enables it`() {
        var enabled: Boolean? = null
        render(actions = AppearanceActions(onDynamicColorChanged = { enabled = it }))

        open("Accent colour, Teal")
        scrollTo("Use the colours from my wallpaper")
        composeRule.onNodeWithText("Use the colours from my wallpaper").assertIsDisplayed()
        composeRule.onNodeWithText("Use the colours from my wallpaper").performClick()

        assertEquals(true, enabled)
    }

    @Test
    fun `wallpaper mode is announced as the selected accent`() {
        render(state = AppearanceUiState(dynamicColor = true))

        composeRule
            .onNodeWithContentDescription("Accent colour, Use the colours from my wallpaper")
            .assertIsDisplayed()
    }

    @Test
    fun `choosing a fixed tint swatch reports it`() {
        var chosen: GlassTint? = null
        render(actions = AppearanceActions(onGlassTintChanged = { chosen = it }))

        open("Tint colour, White")
        composeRule.onNodeWithContentDescription("Warm").performClick()

        assertEquals(GlassTint.Warm, chosen)
    }

    /** Tint offers the fixed washes and every accent, including bundled-theme accent colours. */
    @Test
    fun `tint grid includes every accent without drawing their names`() {
        render(state = AppearanceUiState(backgroundThemes = themes()))

        open("Tint colour, White")

        AccentColor.entries.forEach { accent ->
            composeRule.onNodeWithContentDescription(accent.name).assertIsDisplayed()
            composeRule.onAllNodesWithText(accent.name).assertCountEquals(0)
        }
        themes().forEach { theme ->
            composeRule.onNodeWithContentDescription(theme.name).assertIsDisplayed()
            composeRule.onAllNodesWithText(theme.name).assertCountEquals(0)
        }
    }

    @Test
    fun `choosing an accent as tint pins that colour`() {
        var chosen: GlassTint? = null
        render(actions = AppearanceActions(onGlassTintChanged = { chosen = it }))

        open("Tint colour, White")
        composeRule.onNodeWithContentDescription("Plum").performClick()

        assertEquals(GlassTint.of(AccentScheme.of(AccentColor.Plum)), chosen)
    }

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
    fun `the blur slider is hidden until its summary row is expanded`() {
        var dp: Int? = null
        render(
            state = AppearanceUiState(glassBlurDp = 0),
            actions = AppearanceActions(onGlassBlurChanged = { dp = it }),
        )

        scrollToDescription("Blur, Off")
        composeRule.onAllNodes(hasContentDescription("Blur")).assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Blur, Off").performClick()
        scrollToDescription("Blur")
        composeRule.onNodeWithContentDescription("Blur").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Blur").performSemanticsAction(SemanticsActions.SetProgress) {
            it(GlassBlur.MAX_DP.toFloat())
        }
        assertEquals(GlassBlur.MAX_DP, dp)
    }

    @Test
    fun `a chosen blur radius is shown in the collapsed summary`() {
        render(state = AppearanceUiState(glassBlurDp = GlassBlur.DEFAULT_DP))

        scrollToDescription("Blur, ${GlassBlur.DEFAULT_DP} dp")
        composeRule.onNodeWithContentDescription("Blur, ${GlassBlur.DEFAULT_DP} dp").assertIsDisplayed()
    }

    @Test
    fun `text contrast expands inline and reports the chosen level`() {
        var chosen: TextContrast? = null
        render(actions = AppearanceActions(onTextContrastChanged = { chosen = it }))

        scrollToDescription("Text contrast, Automatic")
        composeRule.onAllNodesWithText("Soft").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Text contrast, Automatic").performClick()
        composeRule.onNodeWithText("Soft").assertIsDisplayed()
        composeRule.onNodeWithText("High").performClick()

        assertEquals(TextContrast.High, chosen)
        composeRule.onAllNodesWithText("Soft").assertCountEquals(0)
    }

    @Test
    fun `choosing a background pack reports the pack`() {
        var chosen: ThemeChoice? = null
        render(
            state = AppearanceUiState(backgroundThemes = themes(), backgroundThemeId = null),
            actions = AppearanceActions(onThemeChoiceChanged = { chosen = it }),
        )

        open("Theme, System")
        composeRule.onNodeWithText("Teal Horizon").performClick()

        assertEquals(ThemeChoice.Pack(themes().first()), chosen)
    }

    @Test
    fun `choosing one of the app's own looks is the way back from a pack`() {
        var chosen: ThemeChoice? = null
        render(
            state = AppearanceUiState(backgroundThemes = themes(), backgroundThemeId = "teal_horizon"),
            actions = AppearanceActions(onThemeChoiceChanged = { chosen = it }),
        )

        open("Theme, Teal Horizon")
        composeRule.onNodeWithText("Light").performClick()

        assertEquals(ThemeChoice.Plain(AppTheme.Light), chosen)
    }

    @Test
    fun `no bundled themes leaves the app's own looks and no talk of pictures`() {
        render(state = AppearanceUiState(backgroundThemes = emptyList()))

        composeRule.onAllNodesWithText("scrolls with you", substring = true).assertCountEquals(0)

        open("Theme, System")
        composeRule.onNodeWithText("AMOLED").assertIsDisplayed()
    }

    @Test
    fun `languages are listed in their own names`() {
        render()

        open("Language, System default")
        scrollTo("Norsk bokmål")
        composeRule.onNodeWithText("Norsk bokmål").assertIsDisplayed()
        composeRule.onNodeWithText("English").assertIsDisplayed()
    }

    @Test
    fun `choosing a language reports it`() {
        var chosen: AppLanguage? = null
        render(actions = AppearanceActions(onLanguageChanged = { chosen = it }))

        open("Language, System default")
        scrollTo("Norsk bokmål")
        composeRule.onNodeWithText("Norsk bokmål").performClick()

        assertEquals(AppLanguage.NorwegianBokmal, chosen)
    }

    @Test
    fun `a text row opens its list in place and closes it on a choice`() {
        render()

        composeRule.onAllNodesWithText("AMOLED").assertCountEquals(0)

        open("Theme, System")
        composeRule.onNodeWithText("AMOLED").assertIsDisplayed()
        composeRule.onNodeWithText("Dark").performClick()
        composeRule.onAllNodesWithText("AMOLED").assertCountEquals(0)
    }

    @Test
    fun `tapping an open text row closes it without choosing`() {
        var chosen: ThemeChoice? = null
        render(actions = AppearanceActions(onThemeChoiceChanged = { chosen = it }))

        open("Theme, System")
        composeRule.onNodeWithText("AMOLED").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Theme, System").performClick()
        composeRule.onAllNodesWithText("AMOLED").assertCountEquals(0)
        assertEquals(null, chosen)
    }

    @Test
    fun `a colour grid closes after a swatch is chosen`() {
        render()

        open("Accent colour, Teal")
        composeRule.onNodeWithContentDescription("Plum").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Plum").performClick()
        composeRule.onAllNodes(hasContentDescription("Plum")).assertCountEquals(0)
    }

    @Test
    fun `the system default is the language shown until one is chosen`() {
        render()

        scrollToDescription("Language, System default")
        composeRule.onNodeWithContentDescription("Language, System default").assertIsDisplayed()
    }

    private fun open(description: String) {
        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasContentDescription(description))
        composeRule.onNodeWithContentDescription(description).performClick()
    }

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

    private fun scrollTo(text: String) = composeRule
        .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        .performScrollToNode(hasText(text, substring = true))

    private fun scrollToDescription(description: String) = composeRule
        .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        .performScrollToNode(hasContentDescription(description))

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
