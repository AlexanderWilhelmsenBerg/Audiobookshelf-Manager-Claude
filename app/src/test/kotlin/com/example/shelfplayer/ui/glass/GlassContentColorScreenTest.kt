package com.example.shelfplayer.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.shelfplayer.core.designsystem.theme.ShelfPlayerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * PRODUCT_SPEC 2.10 — a transparent `Scaffold` container silently makes every word on the screen black.
 *
 * ### The defect this pins, reported from a device
 *
 * *"Now I can't read since the text is black, and on the black theme is black on black."*
 *
 * The cause is one line of Material 3's own signature. `Scaffold`'s `contentColor` defaults to
 * `contentColorFor(containerColor)`, which looks the container up among the scheme's colour *pairs* and,
 * finding no match, falls back to `LocalContentColor.current`. `Color.Transparent` is in no pair. And
 * `LocalContentColor` is `compositionLocalOf { Color.Black }` — `MaterialTheme` does not provide it, only
 * `Surface` does, and this app's root is a `Box`. So the fallback is **literally black**, on every theme,
 * and on the AMOLED theme that is black on black.
 *
 * It is invisible from the code: the container was set for a good reason (the backdrop has to show
 * through), the change reads as being about a background, and nothing anywhere mentions text.
 *
 * ### Why both cases are asserted
 *
 * The first test is the fix. The second is the trap, and it is the one that matters: it fails the moment
 * Material changes that fallback, and until then it is the evidence that the first test is guarding
 * something real rather than restating a default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class GlassContentColorScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a glass screen draws its text in the theme's onSurface`() {
        var content: Color? = null
        var expected: Color? = null
        composeRule.setContent {
            ShelfPlayerTheme(darkTheme = true) {
                expected = MaterialTheme.colorScheme.onSurface
                Scaffold(
                    containerColor = Color.Transparent,
                    contentColor = glassContentColor(),
                ) { padding ->
                    Box(Modifier.padding(padding)) { content = LocalContentColor.current }
                }
            }
        }

        assertEquals(expected, content)
    }

    /** The platform behaviour the fix exists for. If this ever stops being black, say so out loud. */
    @Test
    fun `a transparent container with no content colour falls back to black`() {
        var content: Color? = null
        composeRule.setContent {
            ShelfPlayerTheme(darkTheme = true) {
                Scaffold(containerColor = Color.Transparent) { padding ->
                    Box(Modifier.padding(padding)) { content = LocalContentColor.current }
                }
            }
        }

        assertEquals(Color.Black, content, "Material's fallback changed — the KDoc above needs revisiting")
        assertNotEquals(Color.White, content)
    }
}
