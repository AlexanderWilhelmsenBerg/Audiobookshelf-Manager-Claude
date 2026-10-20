package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import org.junit.Test
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Distinct same-name startup callbacks must each be consumed as discovery, not autoplay. */
class ConnectionHandleIdentityTest {
    @Test
    fun twoSameNameEndpointsAtStartupDoNotBecomeFreshConnectionActions() {
        val connections = DeviceConnections()
        val first = connectionHandleKey(31, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        val second = connectionHandleKey(49, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        assertNotEquals(first, second)
        connections.onPresentAtStart(first)
        connections.onPresentAtStart(second)
        val at = Instant.parse("2026-10-20T08:00:00Z")
        assertFalse(connections.shouldAct(second, at))
        assertFalse(connections.shouldAct(first, at))
    }

    @Test
    fun disconnectAndReconnectInvalidatesOnlyTheAffectedHandleGate() {
        val connections = DeviceConnections()
        val first = connectionHandleKey(31, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        val second = connectionHandleKey(49, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        val at = Instant.parse("2026-10-20T08:00:00Z")
        connections.onPresentAtStart(first)
        connections.onPresentAtStart(second)
        connections.onDisconnected(first)
        assertTrue(connections.shouldAct(first, at))
        assertFalse(connections.shouldAct(second, at))
        assertFalse(connections.shouldAct(first, at))
    }

    @Test
    fun oneHandleCanExposeTwoTransportProfilesWithoutConfusingItsDebounceKey() {
        assertNotEquals(
            connectionHandleKey(7, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            connectionHandleKey(7, AudioDeviceInfo.TYPE_BLE_HEADSET),
        )
    }
}
