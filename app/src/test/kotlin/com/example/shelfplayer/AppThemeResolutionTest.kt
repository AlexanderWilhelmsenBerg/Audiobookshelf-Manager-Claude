package com.example.shelfplayer

import com.example.shelfplayer.core.datastore.ThemeMode
import com.example.shelfplayer.core.model.settings.AccentColor
import com.example.shelfplayer.core.model.settings.AccentScheme
import com.example.shelfplayer.core.model.settings.AppTheme
import com.example.shelfplayer.core.model.settings.BackgroundTheme
import com.example.shelfplayer.core.model.settings.ThemeAccents
import com.example.shelfplayer.core.model.settings.ThemeGlass
import com.example.shelfplayer.core.model.settings.ThemeGround
import com.example.shelfplayer.core.model.settings.ThemeSurfaces
import com.example.shelfplayer.core.model.settings.ThemeText
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PRODUCT_SPEC SET-002 — the theme, read from two stored fields that can disagree.
 *
 * ### Why this is worth its own file
 *
 * Because the failure it guards against is silent and lands on every existing install at once. `theme_mode`
 * is what every build has written; `app_theme_key` is new and empty until the reader opens the new tab. If
 * the key simply won, a reader who chose **Dark** a year ago would be moved to *follow the system* by an
 * update they did not ask for, and would find the app light the next morning. Nothing about that looks like
 * a bug from the inside — the setting reads back exactly as stored.
 */
class AppThemeResolutionTest {

    @Test
    fun `a stored key wins`() {
        val state = AppUiState(appThemeKey = AppTheme.Amoled.key, themeMode = ThemeMode.THEME_MODE_LIGHT)

        assertEquals(AppTheme.Amoled, state.resolveTheme())
    }

    /** The case the file exists for. */
    @Test
    fun `an install that predates the key keeps the brightness it chose`() {
        val dark = AppUiState(appThemeKey = "", themeMode = ThemeMode.THEME_MODE_DARK)
        val light = AppUiState(appThemeKey = "", themeMode = ThemeMode.THEME_MODE_LIGHT)

        assertEquals(AppTheme.Dark, dark.resolveTheme())
        assertEquals(AppTheme.Light, light.resolveTheme())
    }

    /**
     * Proto3's zero value, which is what a device that has never written *either* field reads.
     *
     * `UNRECOGNIZED` is the same answer for a different reason: it is what protobuf hands back for a value
     * written by a newer build, so it is the shape of a downgrade rather than of a fresh install.
     */
    @Test
    fun `an unwritten setting follows the system`() {
        listOf(ThemeMode.THEME_MODE_UNSPECIFIED, ThemeMode.THEME_MODE_SYSTEM, ThemeMode.UNRECOGNIZED)
            .forEach { mode ->
                assertEquals(AppTheme.System, AppUiState(themeMode = mode).resolveTheme(), "for $mode")
            }
    }

    /** A key from a build that shipped a theme this one does not have. */
    @Test
    fun `an unrecognised key falls back rather than throwing`() {
        assertEquals(AppTheme.System, AppUiState(appThemeKey = "solarized").resolveTheme())
    }

    /**
     * The brightness the rest of the app asks for, which AMOLED has to answer as *dark*.
     *
     * `resolveDarkTheme` is what `MainActivity` passes to the colour scheme, and a theme that resolved to
     * light there while painting black surfaces would be black text on black.
     */
    @Test
    fun `AMOLED resolves dark, and System defers to the device`() {
        val amoled = AppUiState(appThemeKey = AppTheme.Amoled.key)
        assertEquals(true, amoled.resolveDarkTheme(systemInDarkTheme = false))

        val system = AppUiState(appThemeKey = AppTheme.System.key)
        assertEquals(true, system.resolveDarkTheme(systemInDarkTheme = true))
        assertEquals(false, system.resolveDarkTheme(systemInDarkTheme = false))
    }

    /**
     * PRODUCT_SPEC SET-002 — *"it should be possible to change the colors"*, reaching the screen.
     *
     * A background pack hands `ShelfPlayerTheme` a complete authored scheme, and the whole point of an
     * override is that it wins. If it also swallowed the accent, the colour dropdown would move nothing
     * while a pack was drawn and the setting would look broken for the exact reader who chose a pack. So
     * a differing accent is imposed on top, and `null` — leave the pack alone — is returned in one case
     * only: the accent already **is** the pack's.
     */
    @Test
    fun `a pack keeps its own accent, and any other accent is imposed over it`() {
        val pack = pack()
        val own = AppUiState(backgroundTheme = pack, accent = AccentScheme.of(pack))
        val chosen = AppUiState(backgroundTheme = pack, accent = AccentScheme.of(AccentColor.Plum))

        assertNull(own.accentArgbFor(isDark = true))
        assertEquals(AccentColor.Plum.darkArgb, chosen.accentArgbFor(isDark = true))
    }

    /** With no pack there is nothing to defer to, so the chosen accent always applies. */
    @Test
    fun `without a pack the accent is always imposed`() {
        val state = AppUiState(accent = AccentScheme.of(AccentColor.Moss))

        assertEquals(AccentColor.Moss.lightArgb, state.accentArgbFor(isDark = false))
        assertEquals(AccentColor.Moss.darkArgb, state.accentArgbFor(isDark = true))
    }

    /** A pack's accent chosen while a *different* pack is drawn is a choice, so it is imposed. */
    @Test
    fun `one pack's colours over another pack's picture are imposed`() {
        val drawn = pack(id = "teal_horizon")
        val borrowed = pack(id = "nebula_glow", primary = 0xFF9FCCFF)
        val state = AppUiState(backgroundTheme = drawn, accent = AccentScheme.of(borrowed))

        assertEquals(0xFF9FCCFF, state.accentArgbFor(isDark = true))
    }

    private fun pack(id: String = "teal_horizon", primary: Long = 0xFF57D6CF) = BackgroundTheme(
        id = id,
        name = id,
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
            primary = primary,
            primaryContainer = 0xFF165D5D,
            onPrimary = 0xFF002625,
            secondary = 0xFF96E6CF,
            tertiary = 0xFF7DBFD9,
            error = 0xFFFF8B91,
        ),
        text = ThemeText(primary = 0xFFF0FFFD, secondary = 0xFFC8E9E5, muted = 0xFF90B7B4, inverse = 0xFF002625),
    )
}
