package com.example.shelfplayer.playback

import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EarlyHeadsetJournalTest {
    @Test
    fun theActiveRunPreservesEarlyMilestonesAtTheirSourceTimeWithFixedNames() {
        val journal = CarLabJournal(RuntimeEnvironment.getApplication())
        val start = Instant.parse("2026-11-28T10:00:00Z")
        journal.begin(start, "case=EarlyTakeBack1000")
        val kinds = listOf("Transferable", "Requested", "TrackReturned", "StreamReturned")
        kinds.forEachIndexed { i, kind ->
            journal.playbackMarker("headset-take-back stage=Early outcome=$kind", start.plusMillis(i.toLong()))
        }
        journal.playbackMarker("headset-take-back stage=Early outcome=PrivateRoute", start)
        val report = journal.attach("{}")
        val runs = JSONObject(report).getJSONObject("playbackTestSuite").getJSONArray("runs")
        val run = runs.getJSONObject(runs.length() - 1)
        val events = run.getJSONArray("events")
        assertEquals("case=EarlyTakeBack1000", run.getString("headsetOutputAtStart"))
        val names = (0 until events.length()).map { events.getJSONObject(it).getString("event") }
        assertEquals(kinds.map { "early-take-back-$it" }, names)
        assertEquals(start.plusMillis(3).toString(), events.getJSONObject(3).getString("atUtc"))
        assertFalse(report.contains("PrivateRoute"))
    }
}
