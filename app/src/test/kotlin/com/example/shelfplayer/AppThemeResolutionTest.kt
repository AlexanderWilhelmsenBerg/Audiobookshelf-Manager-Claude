package com.example.shelfplayer

import com.example.shelfplayer.core.datastore.ThemeMode
import com.example.shelfplayer.core.model.settings.AppTheme
import org.junit.Test
import kotlin.test.assertEquals

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
}
