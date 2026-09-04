package com.example.shelfplayer.core.model.settings

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC SET-002 — the appearance choices, and the rule every one of them shares.
 *
 * A key that no build recognises reads back as the default. That rule is the whole reason these are stored
 * as keys rather than as enum ordinals — see the fields in `app_settings.proto` — and it is what makes a
 * downgrade, or a theme withdrawn in a later build, harmless instead of a crash.
 */
class AppearanceTest {

    @Test
    fun `an unknown key falls back to the default`() {
        assertEquals(AppTheme.Default, AppTheme.ofKey("solarized"))
        assertEquals(AccentColor.Default, AccentColor.ofKey("chartreuse"))
        assertEquals(GlassTint.Default, GlassTint.ofKey("smoke"))
    }

    @Test
    fun `an empty key falls back too`() {
        assertEquals(AppTheme.Default, AppTheme.ofKey(""))
        assertEquals(AccentColor.Default, AccentColor.ofKey(""))
        assertEquals(GlassTint.Default, GlassTint.ofKey(""))
    }

    @Test
    fun `every key round-trips`() {
        AppTheme.entries.forEach { assertEquals(it, AppTheme.ofKey(it.key)) }
        AccentColor.entries.forEach { assertEquals(it, AccentColor.ofKey(it.key)) }
        GlassTint.entries.forEach { assertEquals(it, GlassTint.ofKey(it.key)) }
    }

    /** Two keys that collided would make one choice unreachable, and nothing else would say so. */
    @Test
    fun `keys are unique within each set`() {
        assertEquals(AppTheme.entries.size, AppTheme.entries.map { it.key }.toSet().size)
        assertEquals(AccentColor.entries.size, AccentColor.entries.map { it.key }.toSet().size)
        assertEquals(GlassTint.entries.size, GlassTint.entries.map { it.key }.toSet().size)
    }

    /**
     * **Each accent is genuinely two tones.** One hex used on both grounds is the defect this guards: it
     * would be legible on one theme and invisible on the other, and only on a device would anybody notice.
     */
    @Test
    fun `every accent differs between its light and dark tone`() {
        AccentColor.entries.forEach { accent ->
            assertNotEquals(accent.lightArgb, accent.darkArgb, "${accent.name} uses one tone for both")
            assertEquals(accent.lightArgb, accent.argbFor(isDark = false))
            assertEquals(accent.darkArgb, accent.argbFor(isDark = true))
        }
    }

    /** The dark tone is the lighter one, because it has to sit on a dark ground. */
    @Test
    fun `the dark tone is the brighter of each pair`() {
        AccentColor.entries.forEach { accent ->
            assertTrue(
                accent.darkArgb.roughLuminance() > accent.lightArgb.roughLuminance(),
                "${accent.name} has its tones the wrong way round",
            )
        }
    }

    /** Only the accent-following entry has no colour of its own, and it is the one that borrows. */
    @Test
    fun `the accent tint borrows and the rest do not`() {
        val accentArgb = 0xFF123456
        assertEquals(accentArgb, GlassTint.FollowAccent.argbOr(accentArgb))
        GlassTint.entries.filter { it != GlassTint.FollowAccent }.forEach { tint ->
            assertNotEquals(accentArgb, tint.argbOr(accentArgb), "${tint.name} should carry its own colour")
        }
    }

    @Test
    fun `only AMOLED asks for a flat backdrop`() {
        AppTheme.entries.forEach { theme ->
            assertEquals(theme == AppTheme.Amoled, theme.prefersFlatBackdrop, "for ${theme.name}")
        }
    }

    /**
     * **Off must survive a round trip, and must stay distinguishable from never-chosen.**
     *
     * The whole reason `GlassBlur` has a sentinel. A plain `0` on disk cannot be told from a field nobody
     * wrote, so storing off as zero would read back as the default and turn the blur on again — silently,
     * for the one reader who deliberately switched it off.
     */
    @Test
    fun `blur off round-trips and is not the same as unset`() {
        val storedOff = GlassBlur.toStored(0)

        assertEquals(0, GlassBlur.ofStored(storedOff))
        assertNotEquals(0, storedOff, "off must not be stored as a plain zero")
        assertEquals(GlassBlur.DEFAULT_DP, GlassBlur.ofStored(0), "an unwritten field is the default")
    }

    @Test
    fun `a chosen radius round-trips and is capped`() {
        assertEquals(20, GlassBlur.ofStored(GlassBlur.toStored(20)))
        assertEquals(GlassBlur.MAX_DP, GlassBlur.ofStored(GlassBlur.toStored(GlassBlur.MAX_DP + 40)))
    }

    /** A negative from a corrupt or newer write is not a radius; it must not become one. */
    @Test
    fun `a nonsensical stored radius reads as off or default, never negative`() {
        listOf(-99, -2, -1).forEach { stored ->
            assertTrue(GlassBlur.ofStored(stored) >= 0, "$stored produced a negative radius")
        }
    }

    /**
     * **Every contrast level is a level, and Automatic is the one that defers.**
     *
     * `Automatic` carries `null` because the scheme's own pairing is not a number this enum can name.
     * Any other entry carrying null would silently mean "leave it alone" while claiming to change it.
     */
    @Test
    fun `only Automatic defers to the scheme`() {
        TextContrast.entries.forEach { level ->
            if (level == TextContrast.Automatic) {
                assertEquals(null, level.contrast)
            } else {
                val contrast = level.contrast
                assertTrue(contrast != null, "${level.name} carries no contrast")
                assertTrue(contrast > 0f && contrast <= 1f, "${level.name} is out of range: ${contrast.toDouble()}")
            }
        }
    }

    @Test
    fun `contrast keys round-trip and an unknown one falls back`() {
        TextContrast.entries.forEach { assertEquals(it, TextContrast.ofKey(it.key)) }
        assertEquals(TextContrast.Default, TextContrast.ofKey("sepia"))
        assertEquals(TextContrast.entries.size, TextContrast.entries.map { it.key }.toSet().size)
    }

    /** Enough to order two tones of one hue; not a colour-science claim. */
    private fun Long.roughLuminance(): Int =
        ((this shr 16) and 0xFF).toInt() + ((this shr 8) and 0xFF).toInt() + (this and 0xFF).toInt()
}
