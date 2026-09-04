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

    /**
     * **The app's own looks and the bundled packs are one list.**
     *
     * They were two controls answering the same question, and a pack supersedes the plain theme — so a
     * reader could set *Light*, set a dark pack, and watch the first control go on claiming *Light*. One
     * list cannot express that. Both halves are asserted in one place because the merge is the change.
     */
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

    /**
     * A device that has never chosen shows *System* on the collapsed row, so it is never blank.
     *
     * `AppTheme.Default` is that answer, and it is the state's default — the reconciliation of the two
     * stored fields happens in the view model, which is where `AppearanceViewModelTest` asserts it.
     */
    @Test
    fun `an unwritten setting shows System`() {
        render()

        composeRule.onNodeWithContentDescription("Theme, System").assertIsDisplayed()
    }

    /**
     * The row draws no label of its own — the section header above it already says *Theme*.
     *
     * One node, and it is the heading; a second would be the row repeating it a line later. This is
     * assertable only because a `DropdownRow`'s label keeps its semantics where its *value* does not, which
     * `DropdownRow` explains: a drawn label a test cannot see is a label whose removal a test cannot guard.
     */
    @Test
    fun `the theme row does not repeat its own heading`() {
        render()

        composeRule.onAllNodesWithText("Theme").assertCountEquals(1)

        // The language group is below the fold in a `LazyColumn`, so its heading is not composed until it
        // is scrolled to — and asserting it before scrolling would pass by finding nothing.
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

    /** The theme the whole change was asked for, and the one that carries its own explanation. */
    @Test
    fun `choosing AMOLED reports it and says what it does`() {
        var chosen: ThemeChoice? = null
        render(
            state = AppearanceUiState(theme = AppTheme.Amoled, isDark = true),
            actions = AppearanceActions(onThemeChoiceChanged = { chosen = it }),
        )

        composeRule.onNodeWithText("Dark with true black surfaces.", substring = true).assertIsDisplayed()

        open("Theme, AMOLED")
        composeRule.onNodeWithText("AMOLED").performClick()

        assertEquals(ThemeChoice.Plain(AppTheme.Amoled), chosen)
    }

    /** A pack in force is what the collapsed row names, because the pack is what the reader is looking at. */
    @Test
    fun `a chosen pack is what the theme row shows`() {
        render(state = AppearanceUiState(backgroundThemes = themes(), backgroundThemeId = "teal_horizon"))

        composeRule.onNodeWithContentDescription("Theme, Teal Horizon").assertIsDisplayed()
    }

    /**
     * **Every colour is named.** A list of coloured circles with no words is unusable to a screen reader
     * and to anyone who cannot tell the colours apart — which is a group with an unusually strong reason
     * to be on an appearance screen in the first place. The swatch is decorative; the name is the control.
     */
    @Test
    fun `every accent in the closed palette is named in the list`() {
        render()

        open("Accent colour, Teal")

        AccentColor.entries.forEach { accent ->
            composeRule.onNodeWithText(accent.name).assertIsDisplayed()
        }
    }

    /**
     * The collapsed row says which colour is in force, as one sentence a screen reader can read.
     *
     * Two `Text`s would be announced as two unrelated nodes — "Accent colour", then "Teal" — leaving the
     * listener to assemble the sentence. Both are silenced and the row carries the pair.
     */
    @Test
    fun `the collapsed row announces the setting and its value together`() {
        render()

        composeRule.onNodeWithContentDescription("Theme, System").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Accent colour, Teal").assertIsDisplayed()

        scrollToDescription("Language, System default")
        composeRule.onNodeWithContentDescription("Language, System default").assertIsDisplayed()
    }

    @Test
    fun `choosing an accent reports it`() {
        var chosen: AccentScheme? = null
        render(actions = AppearanceActions(onAccentChanged = { chosen = it }))

        open("Accent colour, Teal")
        composeRule.onNodeWithText("Plum").performClick()

        assertEquals(AccentScheme.of(AccentColor.Plum), chosen)
    }

    /**
     * **Every pack's own scheme is choosable as a colour, and its name is the pack's.**
     *
     * *"The color scheme from the themes, add them to colors."* The list is the closed palette **and** one
     * entry per bundled pack, so a reader can take *Teal Horizon*'s colours without its picture — and can
     * take its picture and then change the colour, which is the other half of the same request.
     */
    @Test
    fun `a background pack's own scheme is offered among the accents`() {
        var chosen: AccentScheme? = null
        render(
            state = AppearanceUiState(backgroundThemes = themes()),
            actions = AppearanceActions(onAccentChanged = { chosen = it }),
        )

        open("Accent colour, Teal")
        composeRule.onNodeWithText("Nebula Glow").assertIsDisplayed()
        composeRule.onNodeWithText("Teal Horizon").performClick()

        assertEquals(AccentScheme.of(themes().first()), chosen)
    }

    /** A pack accent already in force is named after its pack in the collapsed row, not after a hue. */
    @Test
    fun `a chosen pack accent shows the pack's name`() {
        render(
            state = AppearanceUiState(
                backgroundThemes = themes(),
                accent = AccentScheme.of(themes().first()),
            ),
        )

        composeRule.onNodeWithContentDescription("Accent colour, Teal Horizon").assertIsDisplayed()
    }

    @Test
    fun `choosing a tint colour reports it`() {
        var chosen: GlassTint? = null
        render(actions = AppearanceActions(onGlassTintChanged = { chosen = it }))

        open("Tint colour, White")
        composeRule.onNodeWithText("Warm").performClick()

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

    /**
     * **The way back is one of the app's own looks**, which is the job *None* used to do in the pack list.
     *
     * Doing it with the entries a reader would be returning to anyway is the point of the merge: there is
     * no separate "off" to find, and no state in which the theme control and the pack control disagree.
     */
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

    /**
     * With no packs on disk the group is absent rather than empty.
     *
     * An empty picker is a heading over nothing, which reads as a broken screen. The catalog returns an
     * empty list when the assets are unreadable — a build fault — and the honest thing on that path is to
     * show the rest of the tab, not a hole.
     */
    /**
     * With no packs on disk the list is the app's own looks and nothing else, and the hint about pictures
     * is absent.
     *
     * The catalog returns an empty list when the assets are unreadable — a build fault — and the honest
     * thing on that path is a working theme picker, not a hole where one used to be. Before the merge the
     * whole group vanished; now only the half that has nothing to show does.
     */
    @Test
    fun `no bundled themes leaves the app's own looks and no talk of pictures`() {
        render(state = AppearanceUiState(backgroundThemes = emptyList()))

        composeRule.onNodeWithText("scrolls with you", substring = true).assertDoesNotExist()

        open("Theme, System")
        composeRule.onNodeWithText("AMOLED").assertIsDisplayed()
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

        open("Language, System default")

        composeRule.onNodeWithText("Norsk bokmål").assertIsDisplayed()
        composeRule.onNodeWithText("English").assertIsDisplayed()
    }

    @Test
    fun `choosing a language reports it`() {
        var chosen: AppLanguage? = null
        render(actions = AppearanceActions(onLanguageChanged = { chosen = it }))

        open("Language, System default")
        composeRule.onNodeWithText("Norsk bokmål").performClick()

        assertEquals(AppLanguage.NorwegianBokmal, chosen)
    }

    /**
     * The default, on the collapsed row — so it is never blank on a device that has chosen nothing.
     *
     * The two "system" answers on this tab are deliberately worded differently: the theme's says *System*
     * and the language's *System default*, which is what Android's own per-app language picker calls it.
     * They used to share a label, and a test that could not tell them apart is what said so.
     */
    @Test
    fun `the system default is the language shown until one is chosen`() {
        render()

        scrollToDescription("Language, System default")
        composeRule.onNodeWithContentDescription("Language, System default").assertIsDisplayed()
    }

    /**
     * Scrolls to a dropdown and opens it, by the sentence its collapsed row announces.
     *
     * By description rather than by text on purpose, and the distinction is the control's own design: the
     * row's two words are silenced so a screen reader hears one sentence, which also means neither can be
     * confused with the identical word in the list it opens. See `DropdownRow`.
     */
    private fun open(description: String) {
        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasContentDescription(description))
        composeRule.onNodeWithContentDescription(description).performClick()
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

    /** The same, for a dropdown row — whose words are a description rather than text. */
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
