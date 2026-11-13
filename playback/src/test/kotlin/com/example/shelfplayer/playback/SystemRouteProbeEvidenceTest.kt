package com.example.shelfplayer.playback

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemRouteProbeEvidenceTest {
    @Test
    fun `only exact unique identity matches the retained target`() {
        assertEquals("matches", SystemRouteProbeEvidence.targetMatch("headset", "headset", 1, true))
        assertEquals("different", SystemRouteProbeEvidence.targetMatch("car", "headset", 1, true))
        assertEquals("ambiguous", SystemRouteProbeEvidence.targetMatch("headset", "headset", 2, true))
        assertEquals("unavailable", SystemRouteProbeEvidence.targetMatch(null, "headset", 0, true))
        assertEquals("unavailable", SystemRouteProbeEvidence.targetMatch("headset", null, 1, true))
        assertEquals("no-target", SystemRouteProbeEvidence.targetMatch("headset", null, 1, false))
    }

    @Test
    fun `peer aliases correlate handles without disclosing the identity and reset between captures`() {
        val evidence = SystemRouteProbeEvidence()
        val first = evidence.alias("private-address-digest")
        assertEquals(first, evidence.alias("private-address-digest"))
        assertFalse(first.contains("private"))
        evidence.clear()
        assertTrue(first != evidence.alias("private-address-digest"))
    }

    @Test
    fun `bounded aliases cannot reuse an evicted peer label`() {
        val evidence = SystemRouteProbeEvidence()
        val first = evidence.alias("first")
        repeat(100) { evidence.alias("peer-$it") }
        assertTrue(first != evidence.alias("first"))
        assertTrue(evidence.size <= 64)
    }

    @Test
    fun `equal counts still expose peer replacement and missing endpoint does not mean disconnected`() {
        val evidence = SystemRouteProbeEvidence()
        fun profile(identity: String) = listOf(
            BluetoothProfileProbe.Snapshot("a2dp", "observed", listOf(BluetoothProfileProbe.Peer(identity, false))),
        )
        val first = evidence.rows(profile("first"), emptyList(), "first", true) { "unused" }
        val second = evidence.rows(profile("second"), emptyList(), "first", true) { "unused" }
        assertTrue("count=1 peers=b1" in first.first())
        assertTrue("count=1 peers=b2" in second.first())
        assertTrue(first.any { "endpointMatches=0 targetMatch=matches" in it })
        assertTrue(second.any { "targetMatch=different" in it })
        assertFalse((first + second).joinToString().contains("first"))
    }
}
