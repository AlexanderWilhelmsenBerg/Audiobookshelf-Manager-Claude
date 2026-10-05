package com.example.shelfplayer.feature.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.example.shelfplayer.core.model.ServerStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #195/spec21: custom surfaces must not make the connection symbol disappear. */
class HomeStatusContrastTest {
    @Test
    fun `all statuses retain three to one contrast over grayscale and tinted surfaces`() {
        val backgrounds = (0..100).map { step ->
            val value = step / 100f
            Color(value, value, value)
        } + listOf(Color(0xFF8C6A99), Color(0xFF467483), Color(0xFF9E793F))
        backgrounds.forEach { background ->
            ServerStatus.entries.forEach { status ->
                listOf(false, true).forEach { offline ->
                    val tint = homeStatusTint(status, offline, background, neutral = background)
                    val contrast = (maxOf(tint.luminance(), background.luminance()) + 0.05f) /
                        (minOf(tint.luminance(), background.luminance()) + 0.05f)
                    assertTrue(contrast >= 3f, "Status ink must contrast against the active app surface")
                }
            }
        }
    }

    @Test
    fun `offline color does not claim reachability and follows the supplied surface`() {
        val light = homeStatusTint(ServerStatus.Reachable, true, Color.White, Color.Black)
        val dark = homeStatusTint(ServerStatus.Unreachable, true, Color.Black, Color.White)
        assertEquals(Color.Black, light)
        assertEquals(Color.White, dark)
    }

    @Test
    fun `translucent pack surface resolves against the selected app ground`() {
        val surface = Color.White.copy(alpha = 0.1f).compositeOver(Color.Black)
        val tint = homeStatusTint(ServerStatus.Reachable, false, surface, Color.White)
        val lightSurfaceTint = homeStatusTint(ServerStatus.Reachable, false, Color.White, Color.Black)
        assertTrue(tint.luminance() > lightSurfaceTint.luminance())
    }
}
