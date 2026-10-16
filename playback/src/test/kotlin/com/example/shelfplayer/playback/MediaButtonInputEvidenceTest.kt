package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import org.junit.Test
import kotlin.test.assertEquals

/** Media-button provenance must never turn a system/virtual key into a guessed headset. */
class MediaButtonInputEvidenceTest {
    private val a = AudioOutput(
        id = "bluetooth:a",
        displayName = "Headset A",
        kind = DeviceKind.Bluetooth,
        role = AudioOutputRole.Ambiguous,
    )
    private val b = AudioOutput(
        id = "bluetooth:b",
        displayName = "Headset B",
        kind = DeviceKind.Bluetooth,
        role = AudioOutputRole.Ambiguous,
    )

    @Test
    fun `virtual bluetooth AVRCP event cannot identify headset even with matching name`() {
        val result = MediaButtonInputEvidence.classify(0, true, "Headset A", listOf(a, b))
        assertEquals(MediaButtonInputEvidence.Origin.VirtualOrSystem, result.origin)
        assertEquals(0, result.headsetNameMatches)
    }

    @Test
    fun `unknown input device cannot choose between two connected headsets`() {
        val result = MediaButtonInputEvidence.classify(6, null, "Headset A", listOf(a, b))
        assertEquals(MediaButtonInputEvidence.Origin.UnresolvedInput, result.origin)
        assertEquals(0, result.headsetNameMatches)
    }

    @Test
    fun `unique physical input name is evidence candidate only not output authorization`() {
        val result = MediaButtonInputEvidence.classify(6, false, "Headset A", listOf(a, b))
        assertEquals(MediaButtonInputEvidence.Origin.ExternalNameCandidate, result.origin)
        assertEquals(1, result.headsetNameMatches)
    }

    @Test
    fun `duplicate names remain ambiguous`() {
        val duplicate = b.copy(displayName = "Headset A")
        val result = MediaButtonInputEvidence.classify(7, false, "Headset A", listOf(a, duplicate))
        assertEquals(MediaButtonInputEvidence.Origin.ExternalNameAmbiguous, result.origin)
        assertEquals(2, result.headsetNameMatches)
    }

    @Test
    fun `unmatched physical name never selects an unrelated A2DP output`() {
        val result = MediaButtonInputEvidence.classify(7, false, "Unknown AVRCP", listOf(a, b))
        assertEquals(MediaButtonInputEvidence.Origin.ExternalNameUnmatched, result.origin)
        assertEquals(0, result.headsetNameMatches)
    }
}
