package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.DeviceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOutputIdentityTest {
    @Test fun `two same-name Bluetooth devices are ambiguous and cannot verify an audio route`() {
        val result = LiveOutputIdentity.classify(
            listOf(
                bluetooth(11, "bluetooth:buds"),
                bluetooth(22, "bluetooth:buds"),
            ),
        )
        assertEquals(setOf("bluetooth:buds"), result.ambiguousIds)
        assertNull(result.safeRoutedIds(setOf("bluetooth:buds")))
        assertNull(result.safeRoutedIds(setOf("bluetooth:buds", "speaker:unnamed")))
    }

    @Test fun `distinct live Bluetooth names remain selectable and policy-routable`() {
        val result = LiveOutputIdentity.classify(
            listOf(bluetooth(11, "bluetooth:a"), bluetooth(22, "bluetooth:b")),
        )
        assertTrue(result.ambiguousIds.isEmpty())
        assertEquals(setOf("bluetooth:a"), result.safeRoutedIds(setOf("bluetooth:a")))
    }

    @Test fun `duplicate callback rows for one physical handle are not a collision`() {
        val result = LiveOutputIdentity.classify(
            listOf(bluetooth(11, "bluetooth:a"), bluetooth(11, "bluetooth:a")),
        )
        assertFalse(result.isAmbiguous("bluetooth:a"))
    }

    @Test fun `a missing peer restores unique addressing without claiming a permanent hardware identity`() {
        val initial = LiveOutputIdentity.classify(
            listOf(bluetooth(11, "bluetooth:a"), bluetooth(22, "bluetooth:a")),
        )
        val afterRemoval = LiveOutputIdentity.classify(listOf(bluetooth(22, "bluetooth:a")))
        assertTrue(initial.isAmbiguous("bluetooth:a"))
        assertFalse(afterRemoval.isAmbiguous("bluetooth:a"))
        assertEquals(setOf("bluetooth:a"), afterRemoval.safeRoutedIds(setOf("bluetooth:a")))
    }

    @Test fun `wired category policy keeps its historical shared-key semantics`() {
        val result = LiveOutputIdentity.classify(
            listOf(
                LiveOutputIdentity.Handle(4, "wired", DeviceKind.Wired),
                LiveOutputIdentity.Handle(5, "wired", DeviceKind.Wired),
            ),
        )
        assertTrue(result.ambiguousIds.isEmpty())
    }

    @Test fun `unavailable framework route stays unknown rather than becoming confirmed`() {
        val result = LiveOutputIdentity.classify(listOf(bluetooth(11, "bluetooth:a")))
        assertNull(result.safeRoutedIds(null))
    }

    private fun bluetooth(platformId: Int, logicalId: String) =
        LiveOutputIdentity.Handle(platformId, logicalId, DeviceKind.Bluetooth)
}
