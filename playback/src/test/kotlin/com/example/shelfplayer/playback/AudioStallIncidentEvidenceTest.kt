package com.example.shelfplayer.playback

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioStallIncidentEvidenceTest {
    @Test
    fun `first context survives state storms while later errors retain bounded space`() {
        var pinned = AudioStallIncidentEvidence.capture(
            listOf(row("headset-take-back outcome=Requested"), row("output-proof confirmation=Verified")),
            listOf(row("bluetooth-event event=a2dp-connection peer=b2")),
            row("track-progress event=NoOutputProgress"),
        )
        repeat(1_000) {
            pinned =
                AudioStallIncidentEvidence.append(pinned, row("actual-track paused-no-route-measurement"))
        }
        repeat(1_000) {
            pinned =
                AudioStallIncidentEvidence.append(pinned, row("transport-intent requested=Play ordinal=$it"))
        }
        pinned = AudioStallIncidentEvidence.append(pinned, row("playback-error cause1Type=StuckPlayerException"))
        assertTrue(pinned.any { "headset-take-back outcome=Requested" in it })
        assertTrue(pinned.any { "bluetooth-event" in it })
        assertTrue(pinned.last().contains("StuckPlayerException"))
        assertTrue(pinned.size <= AudioStallIncidentEvidence.MAX_ROWS)
        assertTrue(pinned.all(AudioStallIncidentEvidence::isPinned))
    }

    @Test
    fun `ordinary rows do not create an incident and large contexts stay bounded`() {
        assertTrue(AudioStallIncidentEvidence.append(emptyList(), row("output-proof confirmation=Verified")).isEmpty())
        val pinned = AudioStallIncidentEvidence.capture(
            List(500) { row("output-proof confirmation=Verified ordinal=$it") },
            List(500) { row("bluetooth-event peer=b1 ordinal=$it") },
            row("track-progress event=NoOutputProgress"),
        )
        assertEquals(89, pinned.size)
    }

    private fun row(value: String) = "2026-11-17T16:46:36Z $value"
}
