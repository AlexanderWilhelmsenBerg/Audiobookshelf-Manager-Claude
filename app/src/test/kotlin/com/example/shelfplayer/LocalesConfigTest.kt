package com.example.shelfplayer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.model.settings.AppLanguage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC SET-002 (Appearance/accessibility) — the two lists of languages have to be the same list.
 *
 * ### Why there are two, and why they drift
 *
 * `AppLanguage` is what the in-app picker offers. `res/xml/locales_config.xml` is what Android 13's own
 * Settings → Apps → Language offers. Nothing in the build connects them: adding a translation means adding
 * a `values-xx` directory, an enum entry *and* a line of XML, and forgetting either of the last two is
 * silent. A language in the enum with no XML entry is missing from the system picker; a language in the XML
 * with no enum entry can be chosen from Android's Settings and then has no matching entry here, so the app
 * shows *Follow the system* selected while rendering something else.
 *
 * This test is the connection. It also checks the thing both lists depend on — that each language actually
 * has a translation behind it — because a listed language with no `values-xx` directory offers a user their
 * own language and then renders English.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalesConfigTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the manifest's locale list matches the in-app language list`() {
        val declared = declaredLocaleTags()
        val offered = AppLanguage.entries
            .filter { it != AppLanguage.System }
            .map(AppLanguage::tag)
            .toSet()

        assertEquals(offered, declared)
    }

    /**
     * Every offered language resolves a *translated* string.
     *
     * `settings_language` is compared against its English text: if a `values-xx` directory is missing, or
     * the string was never translated in it, the lookup falls back to the default and the two match. That
     * fallback is exactly the failure this catches.
     */
    @Test
    fun `every offered language has a translation behind it`() {
        val english = context.getString(R.string.settings_language)

        AppLanguage.entries.filter { it != AppLanguage.English && it != AppLanguage.System }.forEach { language ->
            val configuration = android.content.res.Configuration(context.resources.configuration)
            configuration.setLocale(java.util.Locale.forLanguageTag(language.tag))
            val translated = context.createConfigurationContext(configuration)
                .getString(R.string.settings_language)

            assertTrue(
                translated != english,
                "${language.tag} resolves to the English string, so it has no translation",
            )
        }
    }

    /** The `android:name` of every `<locale>` in `locales_config.xml`. */
    private fun declaredLocaleTags(): Set<String> {
        val tags = mutableSetOf<String>()
        context.resources.getXml(R.xml.locales_config).use { parser ->
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "locale") {
                    parser.getAttributeValue(ANDROID_NAMESPACE, "name")?.let(tags::add)
                }
                event = parser.next()
            }
        }
        return tags
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
