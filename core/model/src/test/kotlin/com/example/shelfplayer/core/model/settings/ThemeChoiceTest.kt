package com.example.shelfplayer.core.model.settings

import org.junit.Test
import kotlin.test.assertEquals

/**
 * PRODUCT_SPEC SET-002 (Appearance) — two stored fields, one control, and the rule that reconciles them.
 *
 * The app's own themes and the bundled packs were two settings answering the same question, and a pack
 * supersedes the plain theme — so a reader could set *Light*, set a dark pack, and have the first control
 * go on claiming *Light*. Merging the controls is only safe if that reconciliation lives in one place with
 * a test on it; this is that place.
 */
class ThemeChoiceTest {

    private val teal = theme("teal_horizon", "Teal Horizon")
    private val nebula = theme("nebula_glow", "Nebula Glow")

    /** The app's own first, because one of them is the default and they are the way back from a pack. */
    @Test
    fun `the list is the app's own looks and then one per pack`() {
        val all = ThemeChoice.all(listOf(teal, nebula))

        assertEquals(AppTheme.entries.map(ThemeChoice::Plain), all.take(AppTheme.entries.size))
        assertEquals(listOf(ThemeChoice.Pack(teal), ThemeChoice.Pack(nebula)), all.drop(AppTheme.entries.size))
    }

    /** With no packs on disk the list is still every look the app has, rather than empty. */
    @Test
    fun `no packs still leaves the app's own looks`() {
        assertEquals(AppTheme.entries.size, ThemeChoice.all(emptyList()).size)
    }

    /** **A pack wins.** It supersedes the plain theme, so it is what the one control has to show. */
    @Test
    fun `a selected pack is the answer, whatever the plain theme says`() {
        val choice = ThemeChoice.of(AppTheme.Light, packId = teal.id, themes = listOf(teal, nebula))

        assertEquals(ThemeChoice.Pack(teal), choice)
    }

    /** With no pack the plain theme is the answer, including the default nobody has written. */
    @Test
    fun `with no pack the plain theme is the answer`() {
        assertEquals(
            ThemeChoice.Plain(AppTheme.Amoled),
            ThemeChoice.of(AppTheme.Amoled, packId = null, themes = listOf(teal)),
        )
        assertEquals(
            ThemeChoice.Plain(AppTheme.Default),
            ThemeChoice.of(AppTheme.Default, packId = "", themes = emptyList()),
        )
    }

    /**
     * An id naming a pack this build no longer ships falls back to the plain theme.
     *
     * The same rule every stored key here follows, and the one that keeps a removed pack from leaving the
     * control with nothing to draw.
     */
    @Test
    fun `an id naming a pack that has gone falls back to the plain theme`() {
        val choice = ThemeChoice.of(AppTheme.Dark, packId = "solarized", themes = listOf(teal))

        assertEquals(ThemeChoice.Plain(AppTheme.Dark), choice)
    }

    /** Only the identity matters here; the rest is shape the constructor requires. */
    private fun theme(id: String, name: String) = BackgroundTheme(
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
            primary = 0xFF57D6CF,
            primaryContainer = 0xFF165D5D,
            onPrimary = 0xFF002625,
            secondary = 0xFF96E6CF,
            tertiary = 0xFF7DBFD9,
            error = 0xFFFF8B91,
        ),
        text = ThemeText(primary = 0xFFF0FFFD, secondary = 0xFFC8E9E5, muted = 0xFF90B7B4, inverse = 0xFF002625),
    )
}
