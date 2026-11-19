package com.example.shelfplayer.playback

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import com.example.shelfplayer.core.testing.TestAppClock
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BluetoothProbeEventsTest {
    @Test
    fun `connection and disconnection survive a coalesced snapshot without exposing identity`() {
        val events = BluetoothProbeEvents(LiveOutputEndpointRegistry(), TestAppClock())
        val peer = RuntimeEnvironment.getApplication().getSystemService(BluetoothManager::class.java)
            .adapter.getRemoteDevice("12:34:56:78:90:AB")
        events.record(Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, peer))
        events.record(Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, peer))
        val identities = mutableSetOf<String>()
        val rows = events.drain {
            identities += it
            "b1"
        }
        assertEquals(2, rows.size)
        assertTrue("event=acl-connected" in rows[0] && "event=acl-disconnected" in rows[1])
        assertTrue(rows.all { "peer=b1" in it && "observedElapsedMs=" in it })
        assertEquals(1, identities.size)
        assertFalse(rows.joinToString().contains("12:34"))
        assertTrue(events.drain { "unused" }.isEmpty())
    }

    @Test
    fun `state transitions retain previous state and reject unrelated extras and actions`() {
        val events = BluetoothProbeEvents(LiveOutputEndpointRegistry(), TestAppClock())
        events.record(
            Intent(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                .putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTING)
                .putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED)
                .putExtra("private", "private-device-name"),
        )
        events.record(Intent("private.action"))
        val row = events.drain { "unused" }.single()
        assertTrue("previous=1 state=2" in row)
        assertTrue("peer=unavailable" in row)
        assertFalse("private" in row)
    }

    @Test
    fun `burst overflow is explicit and clearing removes queued events`() {
        val events = BluetoothProbeEvents(LiveOutputEndpointRegistry(), TestAppClock())
        repeat(70) { events.record(Intent(BluetoothDevice.ACTION_ACL_CONNECTED)) }
        val rows = events.drain { "unused" }
        assertEquals(65, rows.size)
        assertTrue(rows.first().contains("dropped=6"))
        events.record(Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED))
        events.clear()
        assertTrue(events.drain { "unused" }.isEmpty())
    }
}
