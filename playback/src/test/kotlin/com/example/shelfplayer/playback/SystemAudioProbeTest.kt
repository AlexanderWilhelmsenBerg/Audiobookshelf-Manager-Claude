package com.example.shelfplayer.playback

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import com.example.shelfplayer.core.testing.TestAppClock
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SuppressLint("InlinedApi") // Permission constants are exercised only by the SDK34 fixtures.
class SystemAudioProbeTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = LiveOutputEndpointRegistry()
    private val probe = SystemAudioProbe(context, registry, TestAppClock())

    @Test
    fun `retained identity joins a new handle without changing registry or acquiring focus`() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val first = device(1, "12:34:56:78:90:AB")
        val original = registry.reconcile(listOf(registry.observationOf(first))).single()
        registry.reconcile(emptyList())
        val returned = device(9, "12:34:56:78:90:AB")
        probe.start {}
        try {
            val rows = probe.sample(listOf(returned), original.id) { "o9" }
            assertTrue(rows.any { "targetMatch=matches" in it && "output=o9" in it })
            assertNull(registry.find(returned), "The probe must not reconcile the runtime registry")
            assertNull(shadowOf(context.getSystemService(AudioManager::class.java)).lastAudioFocusRequest)
            assertFalse(rows.any { "12:34" in it || "Private headset" in it || original.id in it })
            assertTrue(rows.any { "focusOwner=unavailable" in it })
        } finally {
            probe.stop()
        }
    }

    @Test
    fun `unknown duplicate and different endpoints cannot claim retained target`() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val first = device(1, "12:34:56:78:90:AB")
        val original = registry.reconcile(listOf(registry.observationOf(first))).single()
        probe.start {}
        try {
            val duplicates = probe.sample(listOf(first, device(2, "12:34:56:78:90:AB")), original.id) { "o$it" }
            assertEquals(2, duplicates.count { "targetMatch=ambiguous" in it })
            val other = probe.sample(listOf(device(3, "12:34:56:78:90:CD")), original.id) { "o3" }
            assertTrue(other.any { "targetMatch=different" in it })
            val redacted = probe.sample(listOf(device(4, "02:00:00:00:00:00")), original.id) { "o4" }
            assertTrue(redacted.any { "identityCapability=Redacted" in it && "targetMatch=unavailable" in it })
        } finally {
            probe.stop()
        }
    }

    @Test
    fun `permission absence and stop never borrow cached identity or restart capture`() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        var changes = 0
        probe.start { changes++ }
        val rows = probe.sample(listOf(device(1, "12:34:56:78:90:AB")), "retained") { "o1" }
        assertTrue(rows.any { "permission-missing" in it })
        assertTrue(rows.any { "identityCapability=PermissionMissing" in it && "peer=unavailable" in it })
        probe.stop()
        val stoppedAt = changes
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(stoppedAt, changes)
        assertTrue(probe.sample(emptyList(), null) { "unused" }.isEmpty())
    }

    @Test
    @Config(sdk = [28])
    fun `older system observes public audio without requiring mode or LE APIs`() {
        probe.start {}
        try {
            val rows = probe.sample(emptyList(), null) { "unused" }
            assertTrue(rows.any { "modeObserver=false" in it && "focusOwner=unavailable" in it })
            assertFalse(rows.any { "profile=le-audio" in it })
        } finally {
            probe.stop()
        }
    }

    @Test
    fun `public playback callback reaches capture but cannot revive it after stop`() {
        val audio = shadowOf(context.getSystemService(AudioManager::class.java))
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        var changes = 0
        probe.start { changes++ }
        shadowOf(Looper.getMainLooper()).idle()
        val initial = changes
        audio.setActivePlaybackConfigurationsFor(listOf(attributes), true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(changes > initial)
        assertTrue(probe.sample(emptyList(), null) { "unused" }.any { "visiblePlayers=1" in it })
        assertNull(audio.lastAudioFocusRequest)
        probe.stop()
        val stoppedAt = changes
        audio.setActivePlaybackConfigurationsFor(emptyList(), true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(stoppedAt, changes)
    }

    private fun device(id: Int, address: String): AudioDeviceInfo {
        val device = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build()
        val port = ReflectionHelpers.getField<Any>(device, "mPort")
        ReflectionHelpers.setField(port, "mName", "Private headset")
        ReflectionHelpers.setField(port, "mAddress", address)
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(port, "mHandle"), "mId", id)
        return device
    }
}
