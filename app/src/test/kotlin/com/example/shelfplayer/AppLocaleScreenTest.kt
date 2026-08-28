package com.example.shelfplayer

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.shelfplayer.core.model.settings.AppLanguage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * PRODUCT_SPEC SET-002 (Appearance/accessibility) — the language setting actually changes the words.
 *
 * ### Why this test is worth having
 *
 * Because the mechanism is invisible. `AppLocale` provides a `Configuration` and a `Context`, and whether
 * `stringResource` reads *those* rather than the activity's is an implementation detail of Compose — one
 * that a version bump could change without any signature moving. A test that renders a real string in a
 * chosen language is the only thing that notices.
 *
 * The platform half (`LocaleManager`, API 33+) is deliberately not asserted: Robolectric's `LocaleManager`
 * is a shadow, so a passing assertion would prove the shadow works. The composition half is the half that
 * has to work on API 26.
 *
 * `settings_language` is the string under test because it is short, translated, and unlikely to be
 * reworded — "Language" in English, "Språk" in Norwegian.
 *
 * ### Why the name ends in `ScreenTest`
 *
 * Because it renders. `app/build.gradle.kts` excludes every class whose name ends in `ScreenTest` from the
 * release unit-test variant, which has no `ComponentActivity` to launch — `ui-test-manifest` is a
 * `debugImplementation`. The suffix is the contract that exclusion relies on, and a rendering test named
 * anything else fails in release with an unresolvable launcher intent.
 * ### Why three SDK levels
 *
 * Because this file is the one place in the app where the API level changes the *mechanism*.
 * `LocaleManager` starts at 33, `minSdk` is 26, and ADR-0022's whole argument is that the composition
 * carries the language on both sides of that line. Running at 26, 33 and 34 is what makes that an assertion
 * rather than a claim: 26 exercises the backport alone, 33 the first release where the platform is told as
 * well, and 34 the ordinary modern case.
 *
 * Every test in the class runs three times. It is worth the seconds here and is not worth it everywhere —
 * `docs/risks.md` R-08 is about the API matrix as a whole, and no JVM run retires it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33, 34], qualifiers = "w411dp-h891dp")
class AppLocaleScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a chosen language renders its own translation`() {
        composeRule.setContent {
            AppLocale(language = AppLanguage.NorwegianBokmal) {
                Text(text = stringResource(R.string.settings_language))
            }
        }

        composeRule.onNodeWithText("Språk").assertIsDisplayed()
    }

    /**
     * The default path, and the one most installs are on. It must leave the device's own configuration
     * alone: `AppLocale` returns early rather than providing a copy, and this is what says the early return
     * still renders.
     */
    @Test
    fun `following the system renders the device's language`() {
        composeRule.setContent {
            AppLocale(language = AppLanguage.System) {
                Text(text = stringResource(R.string.settings_language))
            }
        }

        composeRule.onNodeWithText("Language").assertIsDisplayed()
    }

    /**
     * English is a *choice*, not only the fallback, and it has to override a Norwegian device.
     *
     * This is the case a naive implementation gets wrong by treating "the chosen language equals the
     * default resources" as "nothing to do": on a device set to Norwegian, the default `values` directory is
     * not what `nb` resolves to, so choosing English has to provide a configuration just like any other
     * choice.
     */
    @Test
    @Config(sdk = [34], qualifiers = "nb-rNO-w411dp-h891dp")
    fun `choosing English overrides a Norwegian device`() {
        composeRule.setContent {
            AppLocale(language = AppLanguage.English) {
                Text(text = stringResource(R.string.settings_language))
            }
        }

        composeRule.onNodeWithText("Language").assertIsDisplayed()
    }

    /** The same device, following the system, still reads Norwegian — so the case above proves an override. */
    @Test
    @Config(sdk = [34], qualifiers = "nb-rNO-w411dp-h891dp")
    fun `a Norwegian device following the system reads Norwegian`() {
        composeRule.setContent {
            AppLocale(language = AppLanguage.System) {
                Text(text = stringResource(R.string.settings_language))
            }
        }

        composeRule.onNodeWithText("Språk").assertIsDisplayed()
    }

    // ------------------------------------------------------------ the crash these render tests were blind to

    /**
     * **The provided context must still unwrap to the Activity**, which is what a device found the hard way.
     *
     * `AppLocale` provided `createConfigurationContext(...)` directly. That returns a `ContextImpl`, which is
     * not a `ContextWrapper`, and `androidx.hilt.navigation.HiltViewModelFactory` finds the Activity by
     * walking `ContextWrapper.baseContext`. So the first `hiltViewModel()` inside `AppLocale` threw
     * `IllegalStateException: Expected an activity context … but instead found: android.app.ContextImpl` —
     * and because the language is persisted, every launch afterwards did the same. Recovery needed a
     * reinstall.
     *
     * ### Why this asserts a loop rather than calling Hilt
     *
     * Standing up a Hilt graph in a Robolectric test to reach one factory would test the graph. What broke is
     * a **property of the context**, and [activityInChainOf] is Hilt's own loop copied verbatim from its
     * bytecode, so the thing asserted here is the thing Hilt does. Revert `LocalizedContext` to a raw
     * `createConfigurationContext` and this test fails for the same reason the app did.
     *
     * The four render tests above pass either way, which is why this defect reached hardware:
     * they prove the words change and say nothing about what carries them (`docs/risks.md` R-43).
     */
    @Test
    fun `the localized context unwraps to the activity`() {
        var chain: ComponentActivity? = null

        composeRule.setContent {
            AppLocale(language = AppLanguage.NorwegianBokmal) {
                chain = activityInChainOf(LocalContext.current)
            }
        }

        assertNotNull(chain, "Hilt walks ContextWrapper.baseContext for an Activity and must find one")
    }

    /**
     * And the same context still resolves the chosen language — the two halves in one assertion.
     *
     * Stated together on purpose. The cheap way to stop the crash is to provide no context at all, which
     * fixes the exception and silently removes the feature. This is the test that refuses both mistakes at
     * once: the context has to be Activity-backed **and** localized.
     */
    @Test
    fun `the localized context is both activity-backed and localized`() {
        var chain: ComponentActivity? = null
        var word: String? = null

        composeRule.setContent {
            AppLocale(language = AppLanguage.NorwegianBokmal) {
                val context = LocalContext.current
                chain = activityInChainOf(context)
                word = context.resources.getString(R.string.settings_language)
            }
        }

        assertNotNull(chain, "still Activity-backed")
        assertEquals("Språk", word, "and still Norwegian")
    }

    /**
     * The system path provides nothing, so the Activity is reached trivially — asserted so that the early
     * return cannot start providing a raw context later without this file noticing.
     */
    @Test
    fun `following the system leaves an activity-backed context`() {
        var chain: ComponentActivity? = null

        composeRule.setContent {
            AppLocale(language = AppLanguage.System) {
                chain = activityInChainOf(LocalContext.current)
            }
        }

        assertNotNull(chain)
    }

    /**
     * `androidx.hilt.navigation.HiltViewModelFactory.create`'s search, copied from its bytecode:
     *
     * ```
     * while (context is ContextWrapper) {
     *     if (context is ComponentActivity) return createInternal(context, delegate)
     *     context = context.baseContext
     * }
     * throw IllegalStateException("Expected an activity context …")
     * ```
     *
     * Returns `null` where Hilt throws, so a failure here reads as a missing Activity rather than as an
     * exception from the helper.
     */
    private fun activityInChainOf(context: Context): ComponentActivity? {
        var candidate: Context = context
        while (candidate is ContextWrapper) {
            if (candidate is ComponentActivity) return candidate
            candidate = candidate.baseContext
        }
        return null
    }
}
