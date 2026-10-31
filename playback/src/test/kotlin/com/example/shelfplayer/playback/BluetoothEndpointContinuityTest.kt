package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BluetoothEndpointContinuityTest {
    private val manager get() = shadowOf(RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java))

    @Test
    fun `exact Bluetooth identity survives handle churn without touching healthy playback`() = runTest {
        val fixture = fixture()
        val adapter = fixture.router.localOutputs
        manager.setOutputDevices(listOf(endpoint(1, "10:22:33:44:55:66")))
        adapter.refresh()
        val original = adapter.endpointIds.single()
        fixture.guard(original)
        fixture.measuredRoutes { adapter.endpointIds.singleOrNull()?.let(::setOf) }
        manager.setOutputDevices(listOf(endpoint(2, "10:22:33:44:55:66")))
        fixture.router.playbackCall("refresh")
        runCurrent()
        assertEquals(original, adapter.endpointIds.single())
        assertTrue(fixture.pauseThreads.isEmpty(), "a fresh measured same-device route must keep playing")
        assertTrue(fixture.preferredRequests.isEmpty(), "inventory churn must not reassert a healthy route")
    }

    @Test
    fun `same exact identity returning after absence permits only a fresh muted manual Play`() = runTest {
        val fixture = fixture()
        val adapter = fixture.router.localOutputs
        manager.setOutputDevices(listOf(endpoint(1, "10:22:33:44:55:66")))
        adapter.refresh()
        val original = adapter.endpointIds.single()
        fixture.guard(original)
        fixture.measuredRoutes { null }
        manager.setOutputDevices(emptyList())
        fixture.router.playbackCall("refresh")
        runCurrent()
        assertEquals(ManualPlayProtection.Denied, fixture.router.protectManualPlayOnGuardedHeadset(fixture.player))
        manager.setOutputDevices(listOf(endpoint(3, "10:22:33:44:55:66")))
        fixture.router.playbackCall("refresh")
        runCurrent()
        assertEquals(original, adapter.endpointIds.single())
        assertEquals(
            ManualPlayProtection.MutedAllowed,
            fixture.router.protectManualPlayOnGuardedHeadset(fixture.player),
        )
        assertEquals(0f, fixture.player.volume)
    }

    @Test
    fun `return callback preserves a route already freshly measured healthy`() = runTest {
        val fixture = fixture()
        val adapter = fixture.router.localOutputs
        manager.setOutputDevices(listOf(endpoint(1, "10:22:33:44:55:66")))
        adapter.refresh()
        val original = adapter.endpointIds.single()
        fixture.guard(original)
        fixture.guardOwner().markMissing()
        fixture.measuredRoutes { setOf(original) }
        fixture.router.outputIntentOwner.onBookChanged(1L)
        fixture.router.currentTrackObservation = { AudioTrackObservation(1L, AudioTrackPath.Endpoint(original)) }
        manager.setOutputDevices(listOf(endpoint(2, "10:22:33:44:55:66")))
        fixture.router.playbackCall("refresh")
        runCurrent()
        assertTrue(fixture.pauseThreads.isEmpty())
        assertTrue(fixture.preferredRequests.isEmpty(), "return cannot disrupt a currently verified healthy route")
        assertTrue(fixture.volumeChanges.isEmpty())
    }

    @Test
    fun `same names and missing or placeholder identity cannot inherit a route`() = runTest {
        val adapter = fixture().router.localOutputs
        for (address in listOf("", "02:00:00:00:00:00", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "invalid")) {
            manager.setOutputDevices(listOf(endpoint(1, address)))
            adapter.refresh()
            val original = adapter.endpointIds.single()
            manager.setOutputDevices(listOf(endpoint(2, address)))
            adapter.refresh()
            assertNotEquals(original, adapter.endpointIds.single())
        }
    }

    @Test
    fun `duplicate physical identity revokes retention and cannot borrow the old token`() = runTest {
        val adapter = fixture().router.localOutputs
        manager.setOutputDevices(listOf(endpoint(1, "10:22:33:44:55:66")))
        adapter.refresh()
        val original = adapter.endpointIds.single()
        manager.setOutputDevices(listOf(endpoint(1, "10:22:33:44:55:66"), endpoint(2, "10:22:33:44:55:66")))
        adapter.refresh()
        assertEquals(2, adapter.endpointIds.size)
        assertTrue(original !in adapter.endpointIds)
        manager.setOutputDevices(listOf(endpoint(3, "10:22:33:44:55:66")))
        adapter.refresh()
        assertTrue(original !in adapter.endpointIds)
    }

    private fun endpoint(id: Int, address: String): AudioDeviceInfo =
        AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build().also { device ->
            val port = ReflectionHelpers.getField<Any>(device, "mPort")
            ReflectionHelpers.setField(port, "mName", "Same headset name")
            ReflectionHelpers.setField(port, "mAddress", address)
            val handle = ReflectionHelpers.getField<Any>(port, "mHandle")
            ReflectionHelpers.setField(handle, "mId", id)
        }
}
