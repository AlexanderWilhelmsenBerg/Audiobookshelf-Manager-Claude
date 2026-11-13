package com.example.shelfplayer.playback

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.example.shelfplayer.core.testing.TestAppClock
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothA2dp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ProbeA2dpShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
@SuppressLint("InlinedApi", "MissingPermission") // SDK34 shadow fixtures explicitly control permission outcomes.
class BluetoothProfileProbeTest {
    @Test
    fun `profile proxy supplies exact peer and streaming without opening a device connection`() {
        val f = Fixture()
        f.probe.start {}
        shadowOf(Looper.getMainLooper()).idle()
        val state = f.probe.snapshot().single { it.profile == "a2dp" }
        assertEquals("observed", state.status)
        assertEquals(true, state.peers.single().streaming)
        assertTrue(state.peers.single().identity != null)
        assertFalse(state.peers.single().identity.orEmpty().contains("12:34"))
        assertFalse(f.adapter.isDiscovering)
        f.probe.stop()
        assertFalse(shadowOf(f.adapter).hasActiveProfileProxy(BluetoothProfile.A2DP))
    }

    @Test
    fun `late proxy from stopped capture is closed and cannot notify capture`() {
        val f = Fixture()
        var changes = 0
        f.probe.start { changes++ }
        f.probe.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, changes)
        assertTrue(f.probe.snapshot().all { it.peers.isEmpty() })
        assertFalse(shadowOf(f.adapter).hasActiveProfileProxy(BluetoothProfile.A2DP))
    }

    @Test
    fun `revoked permission clears observations and read failure is not a disconnect`() {
        val f = Fixture()
        f.probe.start {}
        shadowOf(Looper.getMainLooper()).idle()
        Shadow.extract<ProbeA2dpShadow>(f.proxy).failRead = true
        assertEquals("read-failed", f.probe.snapshot().single { it.profile == "a2dp" }.status)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val denied = f.probe.snapshot().single()
        assertEquals("permission-missing", denied.status)
        assertTrue(denied.peers.isEmpty())
        assertFalse(shadowOf(f.adapter).hasActiveProfileProxy(BluetoothProfile.A2DP))
    }

    @Test
    fun `missing permission never binds a profile service`() {
        val f = Fixture()
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        var changes = 0
        f.probe.start { changes++ }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("permission-missing", f.probe.snapshot().single().status)
        assertEquals(0, changes)
    }

    @Test
    fun `public broadcasts reach evidence and permission revocation discards queued identity`() {
        val f = Fixture()
        var changes = 0
        f.probe.start { changes++ }
        shadowOf(Looper.getMainLooper()).idle()
        val baseline = changes
        val context = RuntimeEnvironment.getApplication()
        context.sendBroadcast(Intent(BluetoothDevice.ACTION_ACL_CONNECTED))
        context.sendBroadcast(Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(changes >= baseline + 2)
        val rows = f.probe.drainEvents { "b1" }
        assertEquals(2, rows.size)
        assertTrue("acl-connected" in rows[0] && "acl-disconnected" in rows[1])
        context.sendBroadcast(Intent(BluetoothDevice.ACTION_ACL_CONNECTED))
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        f.probe.snapshot()
        assertTrue(f.probe.drainEvents { "unused" }.isEmpty())
        assertFalse(f.probe.observing)
    }

    private class Fixture {
        private val context: Context = RuntimeEnvironment.getApplication()
        val adapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
        val proxy: BluetoothA2dp = Shadow.newInstanceOf(BluetoothA2dp::class.java)
        val probe =
            BluetoothProfileProbe(
                context,
                LiveOutputEndpointRegistry(),
                Handler(Looper.getMainLooper()),
                TestAppClock(),
            )

        init {
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            shadowOf(adapter).setEnabled(true)
            shadowOf(adapter).setProfileProxy(BluetoothProfile.A2DP, proxy)
            Shadow.extract<ProbeA2dpShadow>(proxy).addDevice(
                adapter.getRemoteDevice("12:34:56:78:90:AB"),
                BluetoothProfile.STATE_CONNECTED,
            )
        }
    }
}

@Implements(BluetoothA2dp::class)
class ProbeA2dpShadow : ShadowBluetoothA2dp() {
    var failRead = false

    @Implementation
    public override fun getConnectedDevices(): List<BluetoothDevice> {
        if (failRead) throw SecurityException("private platform detail")
        return super.getConnectedDevices()
    }

    @Implementation
    fun isA2dpPlaying(device: BluetoothDevice): Boolean = getConnectedDevices().contains(device)
}
