package com.example.shelfplayer.core.model.settings

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC SET-002 (Appearance) — the accent, once a bundled pack's own colours became one of them.
 *
 * Two rules carry the whole feature and both are here rather than in a screen: which accent a pack's
 * colours **are**, and what happens to the accent when the pack changes. The second is the one the owner
 * asked for in two clauses — *"picking a theme chooses the right scheme, but it should be possible to
 * change the colors"* — and a truth table is the only honest way to show both halves hold at once.
 */
class AccentSchemeTest {

    private val teal = theme(id = "teal_horizon", primary = 0xFF57D6CF, container = 0xFF165D5D)
    private val nebula = theme(id = "nebula_glow", primary = 0xFF9FCCFF, container = 0xFF30477C)

    /**
     * A pack's two tones come from the pack, in the roles Material pairs them in.
     *
     * `primary` is the pale tone the author put on their own dark artwork, so it is the **dark**-ground
     * answer; `primaryContainer` is the deep one, so it is the light-ground answer. Nothing is derived —
     * that is what lets a pack accent make the same contrast promise the closed palette makes.
     */
    @Test
    fun `a pack's accent takes both tones from the pack`() {
        val scheme = AccentScheme.of(teal)

        assertEquals(0xFF57D6CF, scheme.argbFor(isDark = true))
        assertEquals(0xFF165D5D, scheme.argbFor(isDark = false))
    }

    /** A built-in keeps the pair it always had, so nothing about the closed palette moved. */
    @Test
    fun `a built-in accent is unchanged by the new type`() {
        AccentColor.entries.forEach { colour ->
            val scheme = AccentScheme.of(colour)

            assertEquals(colour.key, scheme.key)
            assertEquals(colour.lightArgb, scheme.argbFor(isDark = false))
            assertEquals(colour.darkArgb, scheme.argbFor(isDark = true))
            assertFalse(scheme.isFromTheme)
        }
    }

    /**
     * **A pack called `teal` is not the built-in *Teal*.**
     *
     * The prefix is what keeps the two namespaces apart, and this is the case that would silently repaint
     * the app if it did not: one stored key, two possible meanings, and no way for a reader to tell which
     * they got.
     */
    @Test
    fun `a pack whose id matches a built-in is still a different accent`() {
        val collides = theme(id = "teal", primary = 0xFF57D6CF, container = 0xFF165D5D)

        assertEquals("theme:teal", AccentScheme.of(collides).key)
        assertEquals(AccentScheme.of(AccentColor.Teal), AccentScheme.ofKey("teal", listOf(collides)))
        assertEquals(AccentScheme.of(collides), AccentScheme.ofKey("theme:teal", listOf(collides)))
    }

    /** Every stored key round-trips, whichever namespace it is in. */
    @Test
    fun `every offered accent resolves back to itself`() {
        val themes = listOf(teal, nebula)

        AccentScheme.all(themes).forEach { scheme ->
            assertEquals(scheme, AccentScheme.ofKey(scheme.key, themes))
        }
    }

    /** The list is the closed palette and then the packs, so a reader's own colours are not buried. */
    @Test
    fun `the list is the palette first and then one entry per pack`() {
        val all = AccentScheme.all(listOf(teal, nebula))

        assertEquals(AccentColor.entries.size + 2, all.size)
        assertEquals(AccentColor.entries.map { it.key }, all.take(AccentColor.entries.size).map { it.key })
        assertEquals(listOf("theme:teal_horizon", "theme:nebula_glow"), all.takeLast(2).map { it.key })
    }

    /**
     * A key naming a pack this build no longer ships falls back to the default.
     *
     * Not to the built-in whose name follows the prefix, and not to a neighbouring pack: losing a pack
     * must not silently repaint the app in a colour nobody chose.
     */
    @Test
    fun `a key naming a missing pack falls back to the default`() {
        assertEquals(AccentScheme.Default, AccentScheme.ofKey("theme:gone", listOf(teal)))
        assertEquals(AccentScheme.Default, AccentScheme.ofKey("theme:", emptyList()))
        assertEquals(AccentScheme.Default, AccentScheme.ofKey("chartreuse", listOf(teal)))
        assertEquals(AccentScheme.Default, AccentScheme.ofKey("", emptyList()))
    }

    /** **"Picking a theme chooses the right scheme."** */
    @Test
    fun `choosing a pack adopts its accent`() {
        assertEquals(AccentScheme.of(teal), AccentScheme.following(AccentScheme.Default, teal))
        assertEquals(AccentScheme.of(nebula), AccentScheme.following(AccentScheme.of(teal), nebula))
    }

    /** Re-picking the pack already in force writes nothing, so a redundant press cannot churn the store. */
    @Test
    fun `a pack whose accent is already in force needs no write`() {
        assertNull(AccentScheme.following(AccentScheme.of(teal), teal))
    }

    /**
     * **"But it should be possible to change the colors."**
     *
     * A deliberate choice of *Plum* survives turning the pack off. Only an accent that came from a pack is
     * taken back to the default — otherwise choosing *None* would quietly undo a decision the reader made
     * on a different row.
     */
    @Test
    fun `choosing none only undoes an accent that came from a pack`() {
        assertEquals(AccentScheme.Default, AccentScheme.following(AccentScheme.of(teal), theme = null))
        assertNull(AccentScheme.following(AccentScheme.of(AccentColor.Plum), theme = null))
        assertNull(AccentScheme.following(AccentScheme.Default, theme = null))
    }

    /** `belongsTo` is what tells the app to leave a pack's authored roles alone. See `AppUiState`. */
    @Test
    fun `an accent knows which pack it came from`() {
        assertTrue(AccentScheme.of(teal).belongsTo(teal))
        assertFalse(AccentScheme.of(teal).belongsTo(nebula))
        assertFalse(AccentScheme.of(AccentColor.Teal).belongsTo(teal))
    }

    /** Only the accent roles matter here; the rest of a pack is shape the constructor requires. */
    private fun theme(id: String, primary: Long, container: Long) = BackgroundTheme(
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
            primaryContainer = container,
            onPrimary = 0xFF002625,
            secondary = 0xFF96E6CF,
            tertiary = 0xFF7DBFD9,
            error = 0xFFFF8B91,
        ),
        text = ThemeText(primary = 0xFFF0FFFD, secondary = 0xFFC8E9E5, muted = 0xFF90B7B4, inverse = 0xFF002625),
    )
}
