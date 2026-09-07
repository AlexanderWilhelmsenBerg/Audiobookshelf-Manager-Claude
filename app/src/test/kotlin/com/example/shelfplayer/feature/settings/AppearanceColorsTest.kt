package com.example.shelfplayer.feature.settings

import androidx.compose.ui.graphics.Color
import com.example.shelfplayer.core.model.settings.AccentColor
import com.example.shelfplayer.core.model.settings.AccentScheme
import com.example.shelfplayer.core.model.settings.GlassTint
import org.junit.Test
import kotlin.test.assertEquals

class AppearanceColorsTest {
    @Test
    fun `follow accent uses the active theme primary`() {
        val wallpaperAccent = Color(0xFF123456)

        val resolved = GlassTint.FollowAccent.resolvedColor(
            activeAccent = wallpaperAccent,
            storedAccentArgb = AccentScheme.of(AccentColor.Plum).darkArgb,
        )

        assertEquals(wallpaperAccent, resolved)
    }

    @Test
    fun `fixed tint ignores the active theme primary`() {
        val resolved = GlassTint.White.resolvedColor(
            activeAccent = Color(0xFF123456),
            storedAccentArgb = AccentScheme.of(AccentColor.Plum).darkArgb,
        )

        assertEquals(Color(0xFFFFFFFF), resolved)
    }

    @Test
    fun `accent pinned tint does not become follow accent`() {
        val plum = AccentScheme.of(AccentColor.Plum)
        val resolved = GlassTint.of(plum).resolvedColor(
            activeAccent = Color(0xFF123456),
            storedAccentArgb = AccentScheme.of(AccentColor.Teal).darkArgb,
        )

        assertEquals(Color(plum.darkArgb), resolved)
    }
}
