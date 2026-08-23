package com.example.shelfplayer.launcher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.annotation.RequiresApi
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.BuildConfig
import com.example.shelfplayer.R
import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.testing.RecordingLogSink
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC SET-003 — the launcher aliases, against a real package manager.
 *
 * Robolectric resolves components from the merged manifest, so these run against the manifest that
 * actually ships. That is the point: the failure this guards is an enum entry and a manifest entry
 * drifting apart, which no amount of testing the enum against itself would catch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LauncherIconsTest {

    private lateinit var context: Context
    private lateinit var icons: AndroidLauncherIcons

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        icons = AndroidLauncherIcons(
            context = context,
            logger = RedactingLogger(RecordingLogSink(), DefaultRedactor(RedactionPolicy.Default)),
        )
    }

    /**
     * Every entry names an alias that exists.
     *
     * A typo in a component string is invisible until a device runs it — `setComponentEnabledSetting`
     * throws for an unknown component, at which point the user has a settings screen that crashes.
     */
    @Test
    fun `every icon names a declared activity alias`() {
        LauncherIcon.entries.forEach { icon ->
            // MATCH_DISABLED_COMPONENTS, because all but the default are disabled by design and the question
            // here is whether they are *declared*, not whether they are live.
            val info = context.packageManager.getActivityInfo(
                componentOf(icon),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            assertNotNull(info, "${icon.name} has no alias in the manifest")
        }
    }

    /** The alias points at the one activity, so every icon opens the same app. */
    @Test
    fun `every alias targets the main activity`() {
        LauncherIcon.entries.forEach { icon ->
            val info = context.packageManager.getActivityInfo(
                componentOf(icon),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            assertEquals("com.example.shelfplayer.MainActivity", info.targetActivity, icon.name)
        }
    }

    /** A fresh install has made no choice, so what it shows is whatever the manifest enabled. */
    @Test
    fun `the default icon is the one the manifest enables`() {
        assertEquals(LauncherIcon.Default, icons.current())
    }

    /**
     * Android 13's themed icons need a real monochrome layer, not merely a differently named bitmap.
     * Check both resources used by every alias because launchers may prefer `roundIcon` independently.
     */
    @SuppressLint("UseSdkSuppress") // The runner-filter artifact is intentionally absent from JVM tests.
    @RequiresApi(33)
    @Test
    fun `every normal and round adaptive icon supplies a monochrome layer`() {
        assertEquals(LauncherIcon.entries.toSet(), adaptiveIcons.keys)

        adaptiveIcons.forEach { (launcherIcon, resources) ->
            listOf(
                "normal" to resources.normal,
                "round" to resources.round,
            ).forEach { (kind, resource) ->
                val drawable = assertIs<AdaptiveIconDrawable>(
                    context.getDrawable(resource),
                    "${launcherIcon.name} $kind icon is not adaptive",
                )
                assertNotNull(drawable.monochrome, "${launcherIcon.name} $kind icon has no monochrome layer")
            }
        }
    }

    /** The enum and merged manifest must name the same normal icon for each launcher alias. */
    @Test
    fun `every alias points at its expected adaptive icon`() {
        adaptiveIcons.forEach { (launcherIcon, resources) ->
            val info = context.packageManager.getActivityInfo(
                componentOf(launcherIcon),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )

            assertEquals(resources.normal, info.iconResource, launcherIcon.name)
        }
    }

    /** The install/package identity and launcher label are release-facing and must not inherit the namespace. */
    @Test
    fun `the packaged app keeps the BookWave identity`() {
        val expectedApplicationId = "org.homebord.bookwave" + if (BuildConfig.DEBUG) ".debug" else ""
        assertEquals(expectedApplicationId, BuildConfig.APPLICATION_ID)
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        assertEquals(R.string.app_name, context.applicationInfo.labelRes)
        assertEquals("BookWave", context.applicationInfo.loadLabel(context.packageManager).toString())
    }

    /** Upgrade reconciliation must not depend on the listener visiting Settings after installing. */
    @Test
    fun `the package upgrade reconciler is declared and private`() {
        val receiver = context.packageManager.getReceiverInfo(
            ComponentName(context, LauncherIconUpgradeReceiver::class.java),
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )

        assertTrue(receiver.enabled)
        assertFalse(receiver.exported)
    }

    /** An upgrade that adds a new manifest default must not create a second drawer entry. */
    @Test
    fun `an explicitly selected older icon wins over a new manifest default`() {
        context.packageManager.setComponentEnabledSetting(
            componentOf(LauncherIcon.Crimson),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )

        assertEquals(LauncherIcon.Crimson, icons.current())
        assertEquals(1, LauncherIcon.entries.count { enabled(it) })
    }

    /** The old default component represented an explicit Indigo choice when its state was ENABLED. */
    @Test
    fun `an explicitly selected legacy Indigo is migrated to the new Indigo alias`() {
        context.packageManager.setComponentEnabledSetting(
            componentOf(LauncherIcon.BookWave),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        // A partial/crashed older write must be normalized at the same boundary, not carried forward as
        // two drawer entries.
        context.packageManager.setComponentEnabledSetting(
            componentOf(LauncherIcon.Crimson),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )

        assertEquals(LauncherIcon.Indigo, icons.current())
        assertEquals(1, LauncherIcon.entries.count { enabled(it) })
    }

    /** A crashed/corrupt component state with no alias enabled must not strand the app outside the drawer. */
    @Test
    fun `reading an all-disabled state restores the default alias`() {
        LauncherIcon.entries.forEach { icon ->
            context.packageManager.setComponentEnabledSetting(
                componentOf(icon),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        assertEquals(LauncherIcon.Default, icons.current())
        assertEquals(1, LauncherIcon.entries.count { enabled(it) })
        assertTrue(enabled(LauncherIcon.Default))
    }

    @Test
    fun `choosing an icon makes it the current one`() {
        icons.apply(LauncherIcon.Crimson)

        assertEquals(LauncherIcon.Crimson, icons.current())
    }

    /**
     * The invariant the whole mechanism rests on. Two enabled aliases means two entries in the app
     * drawer; zero means the app is gone from it.
     */
    @Test
    fun `exactly one alias is enabled after a change`() {
        icons.apply(LauncherIcon.Vintage)

        assertEquals(1, LauncherIcon.entries.count { enabled(it) }, "exactly one launcher entry")
    }

    @Test
    fun `the app is never absent from the drawer, whichever icon is chosen`() {
        LauncherIcon.entries.forEach { icon ->
            icons.apply(icon)
            assertTrue(LauncherIcon.entries.any { enabled(it) }, "nothing enabled after choosing ${icon.name}")
        }
    }

    /** Switching back and forth is ordinary, and must not accumulate enabled aliases. */
    @Test
    fun `switching repeatedly leaves one alias enabled`() {
        listOf(LauncherIcon.Spectrum, LauncherIcon.Illuminated, LauncherIcon.Default, LauncherIcon.Monochrome)
            .forEach(icons::apply)

        assertEquals(LauncherIcon.Monochrome, icons.current())
        assertEquals(1, LauncherIcon.entries.count { enabled(it) })
    }

    /** Choosing what is already chosen writes nothing, so a recomposition cannot churn the launcher. */
    @Test
    fun `re-choosing the current icon is a no-op`() {
        icons.apply(LauncherIcon.Default)

        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            context.packageManager.getComponentEnabledSetting(componentOf(LauncherIcon.Default)),
            "the manifest default was never overwritten",
        )
    }

    /** Every distinct icon needs its own alias, label, picture, and background. */
    @Test
    fun `no two icons share an alias, a label or a foreground`() {
        assertEquals(LauncherIcon.entries.size, LauncherIcon.entries.map { it.component }.toSet().size)
        assertEquals(LauncherIcon.entries.size, LauncherIcon.entries.map { it.label }.toSet().size)
        assertEquals(LauncherIcon.entries.size, LauncherIcon.entries.map { it.foreground }.toSet().size)
        assertEquals(LauncherIcon.entries.size, LauncherIcon.entries.map { it.background }.toSet().size)
    }

    private fun componentOf(icon: LauncherIcon) = ComponentName(context.packageName, icon.component)

    private fun enabled(icon: LauncherIcon): Boolean =
        when (context.packageManager.getComponentEnabledSetting(componentOf(icon))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == LauncherIcon.Default
            else -> false
        }

    private data class AdaptiveIcons(val normal: Int, val round: Int)

    private companion object {
        val adaptiveIcons = mapOf(
            LauncherIcon.BookWave to AdaptiveIcons(
                normal = R.mipmap.ic_launcher_bookwave,
                round = R.mipmap.ic_launcher_bookwave_round,
            ),
            LauncherIcon.Indigo to AdaptiveIcons(
                normal = R.mipmap.ic_launcher_indigo,
                round = R.mipmap.ic_launcher_indigo_round,
            ),
            LauncherIcon.Spectrum to AdaptiveIcons(
                normal = R.mipmap.ic_launcher_spectrum,
                round = R.mipmap.ic_launcher_spectrum_round,
            ),
            LauncherIcon.Crimson to AdaptiveIcons(
                normal = R.mipmap.ic_launcher_crimson,
                round = R.mipmap.ic_launcher_crimson_round,
            ),
            LauncherIcon.Illuminated to AdaptiveIcons(
                normal = R.mipmap.ic_launcher_illuminated,
                round = R.mipmap.ic_launcher_illuminated_round,
            ),
            LauncherIcon.Vintage to AdaptiveIcons(
                normal = R.mipmap.ic_launcher_vintage,
                round = R.mipmap.ic_launcher_vintage_round,
            ),
            LauncherIcon.Monochrome to AdaptiveIcons(
                normal = R.mipmap.ic_launcher_monochrome,
                round = R.mipmap.ic_launcher_monochrome_round,
            ),
        )
    }
}
