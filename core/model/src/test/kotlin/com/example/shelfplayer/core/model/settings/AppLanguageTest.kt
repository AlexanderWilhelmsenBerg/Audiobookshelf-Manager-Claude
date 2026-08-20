package com.example.shelfplayer.core.model.settings

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC SET-002 (Appearance/accessibility) — the stored tag, read back.
 *
 * The interesting cases are all failures of recognition, because that is the only way this can go wrong at
 * runtime: the writer and the reader are the same build, so a tag only ever fails to resolve after a
 * *downgrade* — an update rolled back, a translation withdrawn — and the answer then has to be the device's
 * own language rather than a `values` directory that is no longer in the APK.
 */
class AppLanguageTest {

    @Test
    fun `every language round-trips through its tag`() {
        AppLanguage.entries.forEach { language ->
            assertEquals(language, AppLanguage.ofTag(language.tag), "did not round-trip: $language")
        }
    }

    /** Proto3's zero value for a string field, which is what a device that never chose reads. */
    @Test
    fun `an empty tag follows the system`() {
        assertEquals(AppLanguage.System, AppLanguage.ofTag(""))
    }

    /**
     * The case a downgrade produces. Falling back to [AppLanguage.System] is what keeps the app readable;
     * throwing, or defaulting to English, would either crash or silently change the language of a device
     * whose owner never asked for either.
     */
    @Test
    fun `a tag this build does not ship follows the system`() {
        assertEquals(AppLanguage.System, AppLanguage.ofTag("de"))
        assertEquals(AppLanguage.System, AppLanguage.ofTag("zxx-Latn-x-nonsense"))
    }

    /**
     * Android's own per-app language picker writes a region, and this app's does not. `nb-NO` and `nb` have
     * to mean the same language or a choice made through Settings would read back as "follow the system"
     * here — and the two pickers would then fight over the same value.
     */
    @Test
    fun `a regional tag resolves to its language`() {
        assertEquals(AppLanguage.NorwegianBokmal, AppLanguage.ofTag("nb-NO"))
        assertEquals(AppLanguage.NorwegianBokmal, AppLanguage.ofTag("nb_NO"))
        assertEquals(AppLanguage.English, AppLanguage.ofTag("EN-GB"))
    }

    /**
     * [AppLanguage.System] is the only entry with no name of its own, and that is load-bearing: the UI uses
     * `displayName == null` to decide which entry gets the translated *Follow the system* label. Every other
     * entry must carry its own endonym, because a translated language list is unreadable to the one person
     * who needs it.
     */
    @Test
    fun `only the system entry has no name of its own`() {
        assertEquals(null, AppLanguage.System.displayName)
        AppLanguage.entries.filter { it != AppLanguage.System }.forEach { language ->
            assertTrue(!language.displayName.isNullOrBlank(), "$language has no name of its own")
            assertTrue(language.tag.isNotBlank(), "$language has no tag")
        }
    }
}
