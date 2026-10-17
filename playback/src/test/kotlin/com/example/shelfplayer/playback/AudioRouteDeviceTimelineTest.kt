package com.example.shelfplayer.playback

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioRouteDeviceTimelineTest {
    private fun device(id: Int, logical: String) =
        AudioRouteDeviceTimeline.Handle(id, logical, "Ambiguous")

    @Test
    fun `transient device loss preserves its privacy-safe process alias on return`() {
        val timeline = AudioRouteDeviceTimeline()
        val first = timeline.observe(listOf(device(27, "bluetooth:A")), "bluetooth:A")
        assertTrue(first.any { it.contains("device-appeared alias=d1") })
        val removed = timeline.observe(emptyList(), "bluetooth:A")
        assertTrue(removed.any { it.contains("device-disappeared alias=d1") })
        val returned = timeline.observe(listOf(device(27, "bluetooth:A")), "bluetooth:A")
        assertTrue(returned.any { it.contains("device-appeared alias=d1") })
        assertEquals("d1", timeline.aliasFor(27))
        assertTrue((first + removed + returned).none { it.contains("bluetooth:A") })
    }

    @Test
    fun `separate runtime handles expose collapsed logical identity without leaking device names`() {
        val timeline = AudioRouteDeviceTimeline()
        val changes = timeline.observe(
            listOf(device(11, "bluetooth:same"), device(12, "bluetooth:same")),
            "bluetooth:same",
        )
        assertTrue(changes.any { it.contains("collisions=1 selection=ambiguous") })
        assertEquals("d1:Ambiguous,d2:Ambiguous", timeline.snapshot())
        assertTrue(changes.none { it.contains("bluetooth:same") })
    }

    @Test
    fun `unmapped track output never invents a known destination`() {
        val timeline = AudioRouteDeviceTimeline()
        timeline.observe(listOf(device(21, "bluetooth:a")), null)
        assertEquals("unmapped", timeline.aliasFor(99))
    }
}
