package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BluetoothEndpointIdentityTest {
    @Test
    fun `identity is canonical process private and not derived from a name`() {
        val identity = BluetoothEndpointIdentity()
        val key = assertNotNull(identity.key(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "10:AB:33:44:55:66"))
        assertEquals(key, identity.key(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "10:ab:33:44:55:66"))
        assertNotEquals(key, BluetoothEndpointIdentity().key(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "10:AB:33:44:55:66"))
        assertTrue(!key.contains(":"))
        assertNull(identity.key(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "10:AB:33:44:55:66"))
        assertNull(identity.key(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "11:AB:33:44:55:66"))
    }

    @Test
    fun `old track handle cannot borrow the replacement identity or a recycled handles new device`() {
        val registry = LiveOutputEndpointRegistry()
        val type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        val first = registry.bluetoothIdentity(type, "10:AB:33:44:55:66")
        val other = registry.bluetoothIdentity(type, "10:AB:33:44:55:77")
        val original = registry.reconcile(
            listOf(LiveOutputEndpointRegistry.Observation(1, type, "Buds", first)),
        ).single()
        val replacement = registry.reconcile(
            listOf(LiveOutputEndpointRegistry.Observation(2, type, "Buds", first)),
        ).single()
        assertEquals(original.id, replacement.id)
        assertNull(registry.find(1, type, "Buds", first))
        assertNull(registry.find(2, type, "Buds"), "missing address evidence cannot authenticate an addressed endpoint")
        registry.reconcile(listOf(LiveOutputEndpointRegistry.Observation(2, type, "Buds", other)))
        assertNull(registry.find(2, type, "Buds", first))
        assertNotEquals(original.id, registry.find(2, type, "Buds", other)?.id)
        registry.clear()
        assertNotEquals(
            original.id,
            registry.reconcile(listOf(LiveOutputEndpointRegistry.Observation(1, type, "Buds", first))).single().id,
        )
    }
}
