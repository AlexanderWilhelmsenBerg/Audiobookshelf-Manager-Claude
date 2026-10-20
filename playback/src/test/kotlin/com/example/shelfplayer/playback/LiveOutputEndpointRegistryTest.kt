package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behavior, not symbols: same-name endpoints must never share route authorization. */
class LiveOutputEndpointRegistryTest {
    private fun seen(id: Int, name: String?) =
        LiveOutputEndpointRegistry.Observation(id, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, name)

    @Test
    fun sameNameA2dpEndpointsRemainDistinctWhileLegacyNameIsOnlyASettingLabel() {
        val registry = LiveOutputEndpointRegistry()
        val endpoints = registry.reconcile(listOf(seen(31, "Buds"), seen(49, "Buds")))
        assertEquals(2, endpoints.size)
        assertNotEquals(endpoints[0].id, endpoints[1].id)
        assertEquals(endpoints[0].knownDeviceId, endpoints[1].knownDeviceId)
        assertEquals("bluetooth:buds", endpoints[0].knownDeviceId)
        assertTrue(endpoints.all { it.id.startsWith("bluetooth:endpoint:e") })
        assertFalse(endpoints.any { it.id.contains("Buds") || it.id.contains("31") || it.id.contains("49") })
        assertEquals(endpoints[1].id, registry.find(49, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds")?.id)
    }

    @Test
    fun reconnectInvalidatesRouteEvenWhenAndroidRecyclesHandle() {
        val registry = LiveOutputEndpointRegistry()
        val original = registry.reconcile(listOf(seen(31, "Buds"))).single().id
        assertEquals(original, registry.reconcile(listOf(seen(31, "Buds"))).single().id)
        registry.reconcile(emptyList())
        assertNull(registry.find(31, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds"))
        assertNotEquals(original, registry.reconcile(listOf(seen(31, "Buds"))).single().id)
    }

    @Test
    fun coldStartDoesNotKnowAnEarlierSessionsOutputs() {
        val first = LiveOutputEndpointRegistry()
        first.reconcile(listOf(seen(1, "Buds"), seen(2, "Other")))
        val old = first.reconcile(listOf(seen(2, "Other"))).single().id
        val next = LiveOutputEndpointRegistry()
        val newOutput = next.reconcile(listOf(seen(2, "Other"))).single().id
        assertNull(next.find(1, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds"))
        assertFalse(RouteEvidence(RouteEvidenceSource.MediaButtonUnknown, RouteConfidence.Unknown, old)
            .confirms(newOutput))
    }

    @Test
    fun unknownAndDuplicateHandlesCannotResolveByName() {
        val registry = LiveOutputEndpointRegistry()
        registry.reconcile(listOf(seen(4, "Buds")))
        assertNull(registry.find(9, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds"))
        assertNull(registry.find(4, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, null))
        assertNull(registry.find(4, AudioDeviceInfo.TYPE_BLE_HEADSET, "Buds"))
        assertTrue(registry.reconcile(listOf(seen(4, "Buds"), seen(4, "Buds"))).isEmpty())
        assertNull(registry.find(4, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds"))
    }

    @Test
    fun onlyConfirmedExactAudioTrackRouteCanAuthorizeUnmute() {
        val id = "bluetooth:endpoint:e1"
        assertTrue(RouteEvidence(RouteEvidenceSource.ActualAudioTrack, RouteConfidence.Confirmed, id).confirms(id))
        for (source in RouteEvidenceSource.entries) {
            for (confidence in RouteConfidence.entries) {
                if (source == RouteEvidenceSource.ActualAudioTrack && confidence == RouteConfidence.Confirmed) continue
                assertFalse(RouteEvidence(source, confidence, id).confirms(id))
            }
        }
        assertFalse(RouteEvidence(RouteEvidenceSource.ActualAudioTrack, RouteConfidence.Confirmed, id)
            .confirms("bluetooth:endpoint:e2"))
        assertFalse(RouteEvidence(RouteEvidenceSource.ActualAudioTrack, RouteConfidence.Unknown).confirms(id))
    }
}
