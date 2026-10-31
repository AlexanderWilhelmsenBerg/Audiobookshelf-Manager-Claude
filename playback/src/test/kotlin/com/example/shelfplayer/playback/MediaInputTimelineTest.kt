package com.example.shelfplayer.playback

import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MediaInputTimelineTest {
    @Test
    fun `shared descriptors remain ambiguous and never expose raw hardware strings`() {
        val timeline = MediaInputTimeline()
        val descriptor = "private-device-address-and-descriptor"
        val rows = timeline.observe(listOf(device(3, descriptor), device(7, descriptor)))
        val report = (rows + timeline.current).joinToString("\n")

        assertContains(report, "inputAlias=i1")
        assertContains(report, "inputAlias=i2")
        assertEquals(2, timeline.current.count { "descriptorAlias=s1 descriptorMatches=2" in it })
        assertContains(report, "association=Unproven")
        assertFalse(descriptor in report)
        assertFalse("Headset" in report)
    }

    @Test
    fun `recycled runtime id gets a new appearance while descriptor equality remains only a hint`() {
        val timeline = MediaInputTimeline()
        val input = device(3)
        timeline.observe(listOf(input))
        assertContains(timeline.eventDevice(input), "inputAlias=i1")
        assertContains(timeline.observe(emptyList()).single { "input-disappeared" in it }, "inputAlias=i1")

        val returned = timeline.observe(listOf(input)).joinToString("\n")
        assertContains(returned, "inputAlias=i2")
        assertContains(returned, "descriptorAlias=s1")
        assertContains(timeline.eventDevice(device(3, "another private device")), "inputAlias=unmapped")
    }

    @Test
    fun `duplicate runtime ids are omitted and both inventory and descriptor alias storage are bounded`() {
        val timeline = MediaInputTimeline()
        val duplicates = timeline.observe(listOf(device(4), device(4, "second"))).joinToString("\n")
        assertContains(duplicates, "duplicateIds=1")
        assertEquals(emptyList(), timeline.current)
        val large = timeline.observe((1..70).map { device(it, "private-$it") }).joinToString("\n")
        assertEquals(64, timeline.current.size)
        assertContains(large, "truncated=true")
        assertFalse("private-" in large)

        val aliases = MediaInputAliases()
        repeat(128) { aliases.descriptor("private-$it") }
        assertEquals("overflow", aliases.descriptor("one-too-many"))
        assertEquals("s1", aliases.descriptor("private-0"))
        assertEquals("missing", aliases.descriptor(null))
        assertEquals("unavailable", aliases.descriptor("x".repeat(1025)))
    }

    private fun device(id: Int, descriptor: String = "private-descriptor") =
        MediaInputDevice(id, 257, 1, virtual = false, external = true, descriptor = descriptor)
}
