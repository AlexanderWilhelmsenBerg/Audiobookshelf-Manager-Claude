package com.example.shelfplayer.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
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
            // MATCH_DISABLED_COMPONENTS, because five of the six are disabled by design and the question
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

    /** Six distinct icons means six distinct aliases, six labels and six pictures. */
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
}
