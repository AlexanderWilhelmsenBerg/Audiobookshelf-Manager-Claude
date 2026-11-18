package com.example.shelfplayer.feature.settings

import com.example.shelfplayer.core.common.log.LogLevel
import com.example.shelfplayer.core.common.log.LoggedEvent
import com.example.shelfplayer.playback.AudioRouteLabState
import com.example.shelfplayer.playback.OutputClassifierState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class UnifiedDiagnosticsJsonTest {
    @Test fun `file export preserves complete UTF-8 JSON instead of truncating a large report`() {
        val report = "{\"schema\":\"bookwave.diagnostics\",\"text\":\"" +
            "Ø-headset-🎧".repeat(20_000) + "\"}"
        val bytes = ByteArrayOutputStream()
        UnifiedDiagnosticsFileExport.write(bytes, report)
        assertEquals(report, bytes.toString(Charsets.UTF_8.name()))
        assertEquals(
            "bookwave-diagnostics-20261021-094740.json",
            UnifiedDiagnosticsFileExport.filename(Instant.parse("2026-10-21T09:47:40Z")),
        )
    }

    @Test fun `timeline is ordered and de-duplicates rows without copying the raw event log`() {
        val instant = Instant.parse("2026-11-18T10:51:00Z")
        val raw = UnifiedDiagnosticsJson.build(
            build = "BookWave test commit",
            environment = UnifiedDiagnosticsJson.Environment(37, 36),
            snapshot = UnifiedDiagnosticsJson.Snapshot(
                recording = true,
                lab = AudioRouteLabState(
                    recording = true,
                    entries = List(2) { "2026-11-18T10:51:02Z user-heard=Skipping" },
                    inputDevices = listOf("inputAlias=i1 association=Unproven"),
                    stallIncident = listOf("2026-11-18T10:51:02Z stall-incident phase=context user-heard=Skipping"),
                ),
                classifier = OutputClassifierState(
                    recording = true,
                    current = listOf("alias=o2 type=BLUETOOTH_A2DP"),
                    entries = listOf("2026-11-18T10:51:01Z output-appeared alias=o2"),
                ),
                events = listOf(
                    LoggedEvent(instant, LogLevel.Info, "Playback", "Info noise"),
                    LoggedEvent(instant, LogLevel.Warn, "Playback", "redacted playback warning"),
                ),
                previousCrash = "redacted crash",
            ),
        )

        val parsed = JSONObject(raw)
        val timeline = parsed.getJSONArray("timeline")
        assertEquals("bookwave.diagnostics", parsed.getString("schema"))
        assertEquals(2, parsed.getInt("schemaVersion"))
        assertEquals(37, parsed.getInt("androidApi"))
        assertEquals(36, parsed.getInt("targetApi"))
        assertTrue(parsed.getBoolean("recording"))
        assertEquals(2, timeline.length())
        assertEquals("2026-11-18T10:51:01Z", timeline.getJSONObject(0).getString("atUtc"))
        assertEquals("system", timeline.getJSONObject(0).getString("source"))
        assertEquals("output-appeared", timeline.getJSONObject(0).getString("event"))
        assertEquals("user-heard", timeline.getJSONObject(1).getString("event"))
        assertEquals("Skipping", timeline.getJSONObject(1).getString("details"))
        assertEquals(1, parsed.getJSONArray("firstIncident").length())
        assertEquals(1, parsed.getJSONArray("problems").length())
        assertEquals(1, parsed.getJSONObject("current").getJSONArray("inputs").length())
        assertEquals("redacted crash", parsed.getString("previousCrash"))
        assertFalse(parsed.has("debugConsole"))
        assertFalse(parsed.has("playbackRace"))
        assertFalse(raw.contains("Info noise"))
    }

    @Test fun `empty capture exports empty collections and omits absent crash`() {
        val report = JSONObject(
            UnifiedDiagnosticsJson.build(
                build = "offline",
                environment = UnifiedDiagnosticsJson.Environment(33, 36),
                snapshot = UnifiedDiagnosticsJson.Snapshot(
                    recording = false,
                    lab = AudioRouteLabState(),
                    classifier = OutputClassifierState(),
                    events = emptyList(),
                    previousCrash = null,
                ),
            ),
        )
        assertEquals(0, report.getJSONArray("timeline").length())
        assertEquals(0, report.getJSONArray("firstIncident").length())
        assertEquals(0, report.getJSONArray("problems").length())
        assertFalse(report.getBoolean("recording"))
        assertFalse(report.has("previousCrash"))
    }
}
