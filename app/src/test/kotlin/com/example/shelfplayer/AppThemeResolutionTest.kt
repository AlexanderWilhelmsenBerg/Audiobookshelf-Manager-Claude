package com.example.shelfplayer

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

/** PRODUCT_SPEC SET-002 — rendering behavior after appearance storage has been resolved by the repository. */
class AppThemeResolutionTest {

    @Test
    fun `the resolved theme is the shell theme`() {
        assertEquals(AppTheme.Amoled, AppUiState(theme = AppTheme.Amoled).resolveTheme())
    }

    @Test
    fun `AMOLED resolves dark, and System defers to the device`() {
        val amoled = AppUiState(theme = AppTheme.Amoled)
        assertEquals(true, amoled.resolveDarkTheme(systemInDarkTheme = false))

        val system = AppUiState(theme = AppTheme.System)
        assertEquals(true, system.resolveDarkTheme(systemInDarkTheme = true))
        assertEquals(false, system.resolveDarkTheme(systemInDarkTheme = false))
    }

    @Test
    fun `a pack keeps its own accent, and any other accent is imposed over it`() {
        val pack = pack()
        val own = AppUiState(backgroundTheme = pack, accent = AccentScheme.of(pack))
        val chosen = AppUiState(backgroundTheme = pack, accent = AccentScheme.of(AccentColor.Plum))

        assertNull(own.accentArgbFor(isDark = true))
        assertEquals(AccentColor.Plum.darkArgb, chosen.accentArgbFor(isDark = true))
    }

    @Test
    fun `without a pack the accent is always imposed`() {
        val state = AppUiState(accent = AccentScheme.of(AccentColor.Moss))

        assertEquals(AccentColor.Moss.lightArgb, state.accentArgbFor(isDark = false))
        assertEquals(AccentColor.Moss.darkArgb, state.accentArgbFor(isDark = true))
    }

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
