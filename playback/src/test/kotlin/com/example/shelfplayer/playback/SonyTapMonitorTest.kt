package com.example.shelfplayer.playback

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SonyTapMonitorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `Sound Connect presence blocks adapter until explicitly disabled or removed`() {
        val packages = context.packageManager
        val shadow = shadowOf(packages)
        val info = PackageInfo().apply {
            packageName = SonyTapMonitor.SOUND_CONNECT
            applicationInfo = ApplicationInfo().apply {
                packageName = SonyTapMonitor.SOUND_CONNECT
                enabled = true
            }
        }
        shadow.installPackage(info)
        assertFalse(SonyTapMonitor.soundConnectAllows(packages))
        packages.setApplicationEnabledSetting(
            SonyTapMonitor.SOUND_CONNECT,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            0,
        )
        assertTrue(SonyTapMonitor.soundConnectAllows(packages))
        shadow.removePackage(SonyTapMonitor.SOUND_CONNECT)
        assertTrue(SonyTapMonitor.soundConnectAllows(packages))
    }

    @Test
    fun `disabled setting empty inventory and closed monitor cannot resolve peers or emit taps`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val monitor = SonyTapMonitor(
            context,
            backgroundScope,
            dispatcher,
            dispatcher,
            onTap = { error("A suppressed monitor emitted a tap") },
            outputForAddress = { error("A suppressed monitor resolved a peer") },
        )
        monitor.update(false, setOf("candidate"))
        monitor.update(true, emptySet())
        monitor.close()
        monitor.update(true, setOf("candidate"))
    }
}
