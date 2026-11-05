package com.example.shelfplayer.playback

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
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
@android.annotation.SuppressLint("UseSdkSuppress") // Robolectric @Config selects SDK; JVM tests have no runner filter.
@androidx.annotation.RequiresApi(31)
class BluetoothEndpointContinuityTest {
    @Before
    fun grantIdentityAccess() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
    }
    private val manager get() = shadowOf(RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java))

    @Test
    fun `only a wearable Bluetooth peer can be the sole inferred headset beside a car`() = runTest {
        val adapter = fixture().router.localOutputs
        val bluetooth = RuntimeEnvironment.getApplication().getSystemService(BluetoothManager::class.java).adapter
        val headset = "11:22:33:44:55:66"
        val car = "22:33:44:55:66:77"
        fun classify(address: String, deviceClass: Int) {
            val classification = ReflectionHelpers.callConstructor(
                BluetoothClass::class.java,
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, deviceClass),
            )
            shadowOf(bluetooth.getRemoteDevice(address)).setBluetoothClass(classification)
        }
        classify(headset, BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES)
        classify(car, BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO)
        manager.setOutputDevices(listOf(endpoint(1, headset, "Headset"), endpoint(2, car, "Car")))
        adapter.refresh()
        val headphoneId = requireNotNull(adapter.buttonOutputForAddress(headset))
        assertEquals(headphoneId, adapter.soleButtonHeadset(adapter.buttonCandidates()))
        classify(car, BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET)
        assertEquals(null, adapter.soleButtonHeadset(adapter.buttonCandidates()))
        manager.setOutputDevices(listOf(endpoint(1, headset), endpoint(2, car)))
        adapter.refresh()
        assertTrue(adapter.buttonCandidates().isEmpty(), "indistinguishable output handles cannot authorize fallback")
        manager.setOutputDevices(listOf(endpoint(2, car)))
        classify(car, BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO)
        adapter.refresh()
        assertTrue(adapter.buttonCandidates().isEmpty())
    }

    @Test
    fun `queued preference cannot borrow a replacement native handle`() = runTest {
        val fixture = fixture()
        val adapter = fixture.router.localOutputs
        manager.setOutputDevices(listOf(endpoint(1, "11:22:33:44:55:66")))
        adapter.refresh()
        val target = adapter.endpointIds.single()
        fixture.measuredRoutes { setOf(target) }
        assertTrue(fixture.router.protectMeasuredPlayingHeadset(target, 1, 0) { true })
        manager.setOutputDevices(listOf(endpoint(2, "11:22:33:44:55:66")))
        adapter.refresh()
        runCurrent()
        assertTrue(fixture.preferredRequests.isEmpty())
        assertTrue(fixture.router.protectMeasuredPlayingHeadset(target, 1, 0) { true })
        runCurrent()
        assertEquals(1, fixture.preferredRequests.size)
    }

    @Test
    fun `healthy automatic preference is reissued only for a new native handle`() = runTest {
        val fixture = fixture()
        val adapter = fixture.router.localOutputs
        manager.setOutputDevices(listOf(endpoint(1, "11:22:33:44:55:66")))
        adapter.refresh()
        val target = adapter.endpointIds.single()
        fixture.measuredRoutes { setOf(target) }
        assertTrue(fixture.router.protectMeasuredPlayingHeadset(target, 1, 0) { true })
        runCurrent()
        assertEquals(1, fixture.preferredRequests.size)
        manager.setOutputDevices(listOf(endpoint(2, "11:22:33:44:55:66")))
        adapter.refresh()
        repeat(3) {
            assertTrue(fixture.router.protectMeasuredPlayingHeadset(target, 1, 0) { true })
            runCurrent()
        }
        assertEquals(2, fixture.preferredRequests.size)
        assertTrue(fixture.pauseThreads.isEmpty())
        assertTrue(fixture.volumeChanges.isEmpty())
    }

    @Test
    fun `denial grant and revoke preserve live appearance but never stale reconnect authority`() = runTest {
        val fixture = fixture()
        val adapter = fixture.router.localOutputs
        val app = shadowOf(RuntimeEnvironment.getApplication())
        app.denyPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        manager.setOutputDevices(listOf(endpoint(1, "11:22:33:44:55:66")))
        adapter.refresh()
        val original = adapter.endpointIds.single()
        assertEquals(
            com.example.shelfplayer.core.model.playback.BluetoothIdentityCapability.PermissionMissing,
            adapter.identityCapability.value,
        )
        assertTrue(!adapter.retainsIdentity(original))
        app.grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        adapter.refresh()
        assertEquals(original, adapter.endpointIds.single())
        assertTrue(adapter.retainsIdentity(original))
        app.denyPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        adapter.refresh()
        assertEquals(original, adapter.endpointIds.single(), "revocation must not reroute a healthy live appearance")
        assertTrue(!adapter.retainsIdentity(original))
        manager.setOutputDevices(emptyList())
        adapter.refresh()
        app.grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        manager.setOutputDevices(listOf(endpoint(2, "11:22:33:44:55:66")))
        adapter.refresh()
        assertNotEquals(original, adapter.endpointIds.single())
        assertTrue(fixture.pauseThreads.isEmpty())
        assertTrue(fixture.preferredRequests.isEmpty())
    }

    @Test
    fun `permission alone cannot turn a masked cached port into verified identity`() = runTest {
        val adapter = fixture().router.localOutputs
        manager.setOutputDevices(listOf(endpoint(1, "XX:XX:XX:XX:55:66")))
        adapter.refresh()
        assertEquals(
            com.example.shelfplayer.core.model.playback.BluetoothIdentityCapability.Redacted,
            adapter.identityCapability.value,
        )
        assertTrue(!adapter.retainsIdentity(adapter.endpointIds.single()))
    }

    @Test
    fun `a current handle losing its identity revokes the old recovery authority`() = runTest {
        val adapter = fixture().router.localOutputs
        manager.setOutputDevices(listOf(endpoint(1, "11:22:33:44:55:66")))
        adapter.refresh()
        val original = adapter.endpointIds.single()
        assertTrue(adapter.retainsIdentity(original))
        manager.setOutputDevices(listOf(endpoint(1, "XX:XX:XX:XX:55:66")))
        adapter.refresh()
        assertTrue(!adapter.retainsIdentity(original))
        assertNotEquals(original, adapter.endpointIds.single())
    }

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

    private fun endpoint(id: Int, address: String, name: String = "Same headset name"): AudioDeviceInfo =
        AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build().also { device ->
            val port = ReflectionHelpers.getField<Any>(device, "mPort")
            ReflectionHelpers.setField(port, "mName", name)
            ReflectionHelpers.setField(port, "mAddress", address)
            val handle = ReflectionHelpers.getField<Any>(port, "mHandle")
            ReflectionHelpers.setField(handle, "mId", id)
        }
}
