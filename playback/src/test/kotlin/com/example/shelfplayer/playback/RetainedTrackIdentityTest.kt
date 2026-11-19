package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import com.example.shelfplayer.core.model.playback.BluetoothIdentityCapability
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetainedTrackIdentityTest {
    private val original = LiveOutputEndpointRegistry.Observation(
        1,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        "Fixture",
        "private-identity",
        BluetoothIdentityCapability.Verified,
    )

    @Test
    fun `missing live appearance requires exact identity and never name or handle`() {
        val registry = LiveOutputEndpointRegistry()
        val id = registry.reconcile(listOf(original)).single().id
        registry.reconcile(emptyList())
        assertEquals(id, registry.retainedPlayingIdentity(original))
        assertNull(registry.find(original.platformId, original.type, original.productName, original.identity))
        assertNull(registry.retainedPlayingIdentity(original.copy(identity = "another-identity")))
        assertNull(registry.retainedPlayingIdentity(original.copy(identity = null)))
        assertNull(registry.retainedPlayingIdentity(original.copy(capability = BluetoothIdentityCapability.Redacted)))
        registry.updateIdentityAccess(false)
        assertNull(registry.retainedPlayingIdentity(original))
    }

    @Test
    fun `old track handle cannot borrow a live successor or a recycled handle`() {
        val registry = LiveOutputEndpointRegistry()
        registry.reconcile(listOf(original))
        registry.reconcile(listOf(original.copy(platformId = 2)))
        assertNull(registry.retainedPlayingIdentity(original))
        registry.reconcile(listOf(original.copy(identity = "replacement")))
        assertNull(registry.retainedPlayingIdentity(original))
    }

    @Test
    fun `duplicate identity and conflicting duplicate handles revoke retained proof`() {
        for (duplicate in listOf(original.copy(platformId = 2), original.copy(identity = "conflict"))) {
            val registry = LiveOutputEndpointRegistry()
            registry.reconcile(listOf(original))
            registry.reconcile(listOf(original, duplicate))
            registry.reconcile(emptyList())
            assertNull(registry.retainedPlayingIdentity(original))
        }
    }
}
