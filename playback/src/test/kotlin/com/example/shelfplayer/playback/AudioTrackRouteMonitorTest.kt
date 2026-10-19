package com.example.shelfplayer.playback

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The actual Android AudioTrack integration needs device acceptance; these pure contracts prevent
 * the diagnostic classifier from turning a missing or unmatched sink into a guessed car/headset.
 */
class AudioTrackRouteMonitorTest {
    @Test
    fun `released and replaced AudioTracks invalidate late route callbacks`() {
        val epoch = AudioTrackObserverEpoch()
        val first = epoch.next()
        assertTrue(epoch.isCurrent(first))
        val next = epoch.next()
        assertFalse(epoch.isCurrent(first))
        assertTrue(epoch.isCurrent(next))
        epoch.next()
        assertFalse(epoch.isCurrent(next))
    }

    @Test
    fun `query generation guard refuses obsolete observations`() {
        val epoch = AudioTrackObserverEpoch()
        epoch.next()
        val duringQuery = epoch.nextSnapshot()
        epoch.next()
        assertFalse(epoch.isCurrent(duringQuery))
    }

    @Test
    fun `unmapped sink remains distinct from the selected headset`() {
        val timeline = AudioRouteDeviceTimeline()
        timeline.observe(
            listOf(AudioRouteDeviceTimeline.Handle(1, "bluetooth:headset", "Ambiguous")),
            "bluetooth:headset",
        )
        assertEquals("unmapped", timeline.aliasFor(23))
    }

    @Test
    fun `aliases label platform handles only within the same process`() {
        val first = AudioRouteDeviceTimeline()
        first.observe(listOf(AudioRouteDeviceTimeline.Handle(10, "bluetooth:a", "Ambiguous")), null)
        assertEquals("d1", first.aliasFor(10))
        val newProcess = AudioRouteDeviceTimeline()
        assertTrue(newProcess.aliasFor(10) == "unmapped")
    }
}
