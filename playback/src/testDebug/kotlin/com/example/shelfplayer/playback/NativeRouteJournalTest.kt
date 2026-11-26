package com.example.shelfplayer.playback

import android.content.Context
import android.media.AudioDeviceInfo
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class NativeRouteJournalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val start = Instant.parse("2026-11-26T09:00:00Z")
    private val headset = RouteReading.devices(listOf(RoutePort(7021, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
    private val speaker = RouteReading.devices(listOf(RoutePort(8032, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))

    @Test
    fun exportDistinguishesActualFirstBothTogetherAndTrackReplacement() {
        val journal = CarLabJournal(context)
        val run = journal.begin(start)
        var ns = 100L
        val samples = NativeRouteSamples { ++ns }
        fun read(epoch: Long, preferred: RouteReading, actual: RouteReading, current: Boolean = true) {
            samples.observe("poll", epoch, { NativeRoutePair(preferred, actual) }, { current })
        }
        read(1, headset, headset)
        read(1, headset, headset)
        read(1, headset, speaker)
        read(1, speaker, speaker, current = false)
        read(1, RouteReading("default"), speaker)
        read(1, headset, headset)
        read(2, headset, RouteReading("not-playing"))
        journal.routes(run, samples.drain(), start.plusSeconds(1))
        journal.finish("finished", start.plusSeconds(2))
        val second = journal.begin(start.plusSeconds(3))
        val json = JSONObject(journal.attach("{}")).getJSONObject("playbackTestSuite")
        assertEquals(second, json.getInt("activeRunId"))
        val rows = json.getJSONArray("runs").getJSONObject(0).getJSONArray("nativeRouteSamples")
        assertEquals(5, rows.length())
        assertEquals(
            listOf("baseline", "actual", "preferred", "both", "track-changed"),
            (0 until rows.length()).map { rows.getJSONObject(it).getString("change") },
        )
        val baseline = rows.getJSONObject(0)
        val target = baseline.getJSONObject("preferred").getJSONArray("outputs").getJSONObject(0).getString("alias")
        assertEquals(
            target,
            rows.getJSONObject(1).getJSONObject("preferred").getJSONArray("outputs")
                .getJSONObject(0).getString("alias"),
        )
        assertEquals("not-playing", rows.getJSONObject(4).getJSONObject("actual").getString("status"))
        assertTrue(rows.getJSONObject(1).getLong("elapsedRealtimeNs") > baseline.getLong("readEndElapsedRealtimeNs"))
        assertFalse(rows.toString().contains("7021"))
        assertFalse(rows.toString().contains("8032"))
    }

    @Test
    fun repeatAndUpgradeKeepOlderRunsAndRejectLateWrites() {
        val prefs = context.getSharedPreferences("car-playback-tests", Context.MODE_PRIVATE)
        val legacy = JSONObject().put("runId", 1).put("caseId", "Vlc").put("startUtc", start.toString())
            .put("lastObservedUtc", start.plusSeconds(1).toString())
        prefs.edit().putInt("next", 2).putString("run-1", legacy.toString()).commit()
        val journal = CarLabJournal(context)
        val id = journal.begin(start.plusSeconds(2))
        val samples = NativeRouteSamples { 100L }
        samples.observe("run-start", null, { NativeRoutePair.NoTrack }, { true })
        journal.routes(1, samples.drain(), start.plusSeconds(3))
        val suite = JSONObject(journal.attach("{}")).getJSONObject("playbackTestSuite")
        val runs = suite.getJSONArray("runs")
        assertEquals("process-interrupted", runs.getJSONObject(0).getString("endReason"))
        assertEquals("Vlc", runs.getJSONObject(0).getString("caseId"))
        assertEquals(id, runs.getJSONObject(1).getInt("runId"))
        assertEquals(0, runs.getJSONObject(1).getJSONArray("nativeRouteSamples").length())
    }
}
