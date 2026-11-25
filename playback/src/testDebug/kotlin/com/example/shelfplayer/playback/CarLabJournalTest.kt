package com.example.shelfplayer.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CarLabJournalTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val origin = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun repeatedCasesLabelEventsAcrossSwitchesAndGaps() {
        val lab = CarLabJournal(context)
        val first = lab.begin(CarLabCase.Platform, origin)
        val second = lab.begin(CarLabCase.Platform, origin.plusSeconds(2))
        lab.finish("finished", origin.plusSeconds(GAP_START.toLong()))
        lab.heard(second, CarLabPhase.Disconnect, CarLabHeard.Skip)
        val third = lab.begin(CarLabCase.Pcm, origin.plusSeconds(THIRD_START.toLong()))
        val rows = JSONArray(
            (0..THIRD_START).map { second ->
                JSONObject().put("atUtc", origin.plusSeconds(second.toLong()).toString())
            },
        )
        val report = JSONObject(lab.attach(JSONObject().put("timeline", rows).put("firstIncident", rows).toString()))
        for (key in listOf("timeline", "firstIncident")) {
            val timeline = report.getJSONArray(key)
            assertEquals(first, timeline.getJSONObject(1).getInt("runId"))
            assertEquals(second, timeline.getJSONObject(2).getInt("runId"))
            assertFalse(timeline.getJSONObject(GAP_START).has("runId"))
            assertEquals("Pcm", timeline.getJSONObject(THIRD_START).getString("caseId"))
        }
        val suite = report.getJSONObject("playbackTestSuite")
        assertEquals(third, suite.getInt("activeRunId"))
        val runs = suite.getJSONArray("runs")
        assertEquals("next-test", runs.getJSONObject(0).getString("endReason"))
        assertEquals("Skip", runs.getJSONObject(1).getJSONArray("observations").getJSONObject(0).getString("heard"))
    }

    @Test
    fun processRestartEndsThePreviousIntervalAndKeepsItsLastObservation() {
        val lab = CarLabJournal(context)
        val id = lab.begin(CarLabCase.Exo, origin)
        lab.record("sample-started", now = origin.plusSeconds(1))
        val restored = CarLabJournal(context)
        val rows = JSONArray(listOf(1L, 2L).map { JSONObject().put("atUtc", origin.plusSeconds(it).toString()) })
        val json = JSONObject(restored.attach(JSONObject().put("timeline", rows).toString()))
        assertEquals(id, json.getJSONArray("timeline").getJSONObject(0).getInt("runId"))
        assertFalse(json.getJSONArray("timeline").getJSONObject(1).has("runId"))
        assertTrue(json.getJSONObject("playbackTestSuite").isNull("activeRunId"))
        assertEquals("process-interrupted", restored.status.value.message)
        restored.clear()
        assertEquals("{}", CarLabJournal(context).attach("{}"))
    }

    @Test
    fun stoppingTheSampleKeepsTheDisconnectInTheSameRun() {
        val journal = CarLabJournal(context)
        val id = journal.begin(CarLabCase.Preferred)
        journal.sampleStopped("output-moved")
        journal.record("projection", JSONObject().put("state", "NotConnected"))
        val suite = JSONObject(journal.attach("{}")).getJSONObject("playbackTestSuite")
        assertEquals(id, suite.getInt("activeRunId"))
        val run = suite.getJSONArray("runs").getJSONObject(0)
        assertEquals("output-moved", run.getString("sampleStopReason"))
        assertEquals("projection", run.getJSONArray("events").getJSONObject(1).getString("event"))
    }

    private companion object {
        const val GAP_START = 4
        const val THIRD_START = 6
    }
}
