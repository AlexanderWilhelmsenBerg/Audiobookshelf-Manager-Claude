package com.example.shelfplayer.feature.settings

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.shelfplayer.core.designsystem.theme.BookWaveDesign
import com.example.shelfplayer.core.designsystem.theme.ShelfPlayerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeContrastScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `derived primary labels remain readable in Dark and AMOLED`() {
        // Celestial Blue, Twilight Amethyst, and a grey that needs pure black instead of the warm ink.
        val accents = listOf(Color(0xFF66AFFF), Color(0xFFB69CFF), Color(0xFF777777))
        val rendered = mutableMapOf<Triple<BookWaveDesign, Boolean, Color>, ColorScheme>()
        compose.setContent {
            for (design in BookWaveDesign.entries) {
                for (pureBlack in listOf(false, true)) {
                    for (accent in accents) {
                        ShelfPlayerTheme(darkTheme = true, pureBlack = pureBlack, accent = accent, design = design) {
                            val scheme = MaterialTheme.colorScheme
                            SideEffect { rendered[Triple(design, pureBlack, accent)] = scheme }
                        }
                    }
                }
            }
        }

        compose.runOnIdle {
            assertEquals(BookWaveDesign.entries.size * 2 * accents.size, rendered.size)
            rendered.forEach { (choice, scheme) ->
                assertEquals(choice.third, scheme.primary)
                assertReadable(scheme.onPrimary, scheme.primary)
                assertReadable(scheme.onPrimaryContainer, scheme.primaryContainer)
            }
        }
    }

    @Test
    fun `an active background pack retains its authored primary foregrounds`() {
        val authored = darkColorScheme(
            primary = Color(0xFF66AFFF),
            onPrimary = Color(0xFF0B2545),
            primaryContainer = Color(0xFF183663),
            onPrimaryContainer = Color(0xFFD9EBFF),
        )
        lateinit var rendered: ColorScheme
        compose.setContent {
            ShelfPlayerTheme(darkTheme = true, override = authored) {
                val scheme = MaterialTheme.colorScheme
                SideEffect { rendered = scheme }
            }
        }

        compose.runOnIdle {
            assertEquals(authored.onPrimary, rendered.onPrimary)
            assertEquals(authored.onPrimaryContainer, rendered.onPrimaryContainer)
        }
    }

    private fun assertReadable(foreground: Color, background: Color) {
        val first = foreground.luminance()
        val second = background.luminance()
        val contrast = (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
        assertTrue(contrast >= 4.5f, "Expected 4.5:1 text contrast on $background, got $contrast:1")
    }
}
