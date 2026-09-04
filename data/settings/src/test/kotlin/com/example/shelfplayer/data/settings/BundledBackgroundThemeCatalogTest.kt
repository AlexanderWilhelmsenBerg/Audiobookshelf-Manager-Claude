package com.example.shelfplayer.data.settings

import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.settings.AccentScheme
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC SET-002 (Appearance) — every bundled theme pack, parsed.
 *
 * ### What this buys back
 *
 * The packs are JSON on disk rather than Kotlin constants, which is what makes adding a theme a matter of
 * dropping a directory in. The cost of that choice is that a malformed pack is a runtime problem instead
 * of a compile error — so this test is the compile error: it parses **every** theme the manifest names and
 * asserts the shape the app depends on. A pack that would have shown a black screen on a device fails the
 * build here instead.
 *
 * It reads the module's own assets, which is why it is in this module: the packs live beside the code that
 * reads them, and a test that had to reach into `:app`'s merged assets could not run without it.
 */
@RunWith(RobolectricTestRunner::class)
class BundledBackgroundThemeCatalogTest {

    /**
     * A `TestDispatcher` rather than `Unconfined`, which detekt's `InjectDispatcher` rule asks for and
     * which is also the honest choice: the catalog's IO is the thing under test, and running it on a
     * dispatcher the test controls is what keeps the read deterministic.
     */
    private val dispatcher = StandardTestDispatcher()

    private val catalog = BundledBackgroundThemeCatalog(
        context = ApplicationProvider.getApplicationContext(),
        io = dispatcher,
        logger = RecordingLogger(),
    )

    @Test
    fun `every bundled theme parses`() = runTest(dispatcher) {
        val themes = catalog.themes()

        assertEquals(EXPECTED_THEMES, themes.size, "themes: ${themes.map { it.id }}")
        assertEquals(EXPECTED_THEMES, themes.map { it.id }.toSet().size, "ids are not unique")
    }

    /**
     * **Every bundled pack's accent is a usable pair, measured rather than assumed.**
     *
     * `AccentScheme` takes a pack's `primary` as its dark-ground tone and `primaryContainer` as its light-
     * ground one, on the strength of one sentence: the packs are authored dark, so the first is pale and
     * the second deep. That sentence is a claim about six JSON files nobody in this codebase wrote, and if
     * it were wrong for one of them the failure would be a reader choosing that pack's colours on a light
     * theme and getting pale-on-white — unreadable, and only visible on a device.
     *
     * So it is measured. R-101 is the same lesson from the same packs: the one called *Light Pack* is dark,
     * and believing the name would have shipped it as a light theme.
     */
    @Test
    fun `every pack's accent is dark enough for paper and light enough for its own artwork`() = runTest(dispatcher) {
        catalog.themes().forEach { theme ->
            val scheme = AccentScheme.of(theme)
            val onPaper = scheme.argbFor(isDark = false).luminance()
            val onArtwork = scheme.argbFor(isDark = true).luminance()

            assertTrue(onPaper < MIDPOINT, "${theme.id}: the light-ground accent is not dark ($onPaper)")
            assertTrue(onArtwork > MIDPOINT, "${theme.id}: the dark-ground accent is not light ($onArtwork)")
        }
    }

    /**
     * **Every colour is opaque unless the pack said otherwise, and none is zero.**
     *
     * A zero here means a role parsed to fully transparent black — the shape of a colour that silently
     * failed to read — and it is invisible on a device until the surface it belongs to disappears.
     */
    @Test
    fun `no theme has a colour that failed to read`() = runTest(dispatcher) {
        catalog.themes().forEach { theme ->
            listOf(
                "base" to theme.ground.base,
                "surface" to theme.surfaces.card,
                "accent" to theme.accents.primary,
                "textPrimary" to theme.text.primary,
                "error" to theme.accents.error,
            ).forEach { (role, value) ->
                assertTrue(value != 0L, "${theme.id}.$role is zero")
            }
        }
    }

    /**
     * **All six are dark**, including the three from the pack called *Light*.
     *
     * Recorded as an assertion because it is the fact most likely to be assumed wrong by the next person
     * to read the pack names — see `BackgroundTheme`. If a genuinely light pack is added later this fails,
     * which is the moment to check that the light-text mapping still holds.
     */
    @Test
    fun `every bundled theme is dark, whichever pack it came from`() = runTest(dispatcher) {
        catalog.themes().forEach { theme ->
            assertTrue(theme.isDark, "${theme.id} (${theme.pack}) parsed as light")
            assertTrue(theme.text.primary.luminance() > theme.ground.base.luminance(), "${theme.id} text is darker")
        }
    }

    @Test
    fun `a theme is findable by id, and an unknown id is null`() = runTest(dispatcher) {
        val first = catalog.themes().first()

        assertEquals(first, catalog.theme(first.id))
        assertEquals(null, catalog.theme("no_such_theme"))
    }

    @Test
    fun `every theme names an asset that exists`() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        catalog.themes().forEach { theme ->
            context.assets.open(theme.ground.asset).use { it.read() }
        }
    }

    @Test
    fun `both notations parse and anything else is refused`() {
        assertEquals(0xFF112233L, argb("#112233"), "six digits must come back opaque")
        assertEquals(0x80112233L, argb("#80112233"))
        assertFailsWith<IllegalArgumentException> { argb("#12345") }
        assertFailsWith<IllegalArgumentException> { argb("teal") }
    }

    private class RecordingLogger : Logger {
        override fun log(event: LogEvent) = Unit
    }

    private companion object {
        /**
         * Where "light enough to need dark text on it" begins.
         *
         * The same figure `ShelfPlayerTheme.contrastOn` splits on, so a pack that passes here is one whose
         * accent that function will pair the way the pack author expected.
         */
        const val MIDPOINT = 0.5

        /** Three per pack. A theme added without updating this is a theme nobody asserted. */
        const val EXPECTED_THEMES = 6
    }
}
