package com.example.shelfplayer.feature.settings

import com.example.shelfplayer.core.common.log.LogLevel
import com.example.shelfplayer.core.common.log.LoggedEvent
import com.example.shelfplayer.core.common.log.RaceCaptureState
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
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class UnifiedDiagnosticsJsonTest {
    @Test fun `JSON combines independent source records and remains parseable`() {
        val raw = UnifiedDiagnosticsJson.build(
            build = "BookWave test commit",
            active = true,
            lab = AudioRouteLabState(
                recording = true,
                entries = listOf("2026-10-19T12:00:00Z actual-track playing-to=d2:Ambiguous"),
                route = "selection=Automatic",
                reportStatus = "playing-to=d2:Ambiguous",
            ),
            classifier = OutputClassifierState(
                recording = true,
                current = listOf("alias=o2 type=BLUETOOTH_A2DP"),
                entries = listOf("2026-10-19T12:00:01Z output-appeared alias=o2"),
            ),
            eventLog = listOf(
                LoggedEvent(
                    at = Instant.parse("2026-10-19T12:00:00Z"),
                    level = LogLevel.Info,
                    tag = "Playback",
                    line = "redacted transport event",
                ),
            ),
            race = RaceCaptureState(recording = true, lines = listOf("+25ms hardware Play")),
            debugConsole = "redacted snapshot",
        )
        val parsed = JSONObject(raw)
        assertEquals("bookwave.diagnostics", parsed.getString("schema"))
        assertEquals(1, parsed.getInt("schemaVersion"))
        assertTrue(parsed.getJSONObject("recording").getBoolean("unified"))
        assertEquals(1, parsed.getJSONObject("audioRouteLab").getJSONArray("events").length())
        assertEquals(1, parsed.getJSONObject("outputClassifier").getJSONArray("current").length())
        assertEquals("Playback", parsed.getJSONArray("eventLog").getJSONObject(0).getString("tag"))
        assertEquals("+25ms hardware Play", parsed.getJSONArray("playbackRace").getString(0))
        assertEquals("redacted snapshot", parsed.getString("debugConsole"))
        assertFalse(raw.contains("192.168.40."))
    }

    @Test fun `empty captures remain typed JSON collections and do not manufacture measurements`() {
        val report = JSONObject(
            UnifiedDiagnosticsJson.build(
                build = "offline",
                active = false,
                lab = AudioRouteLabState(),
                classifier = OutputClassifierState(),
                eventLog = emptyList(),
                race = RaceCaptureState(),
                debugConsole = "",
            ),
        )
        assertEquals(0, report.getJSONArray("eventLog").length())
        assertEquals(0, report.getJSONObject("audioRouteLab").getJSONArray("events").length())
        assertFalse(report.getJSONObject("recording").getBoolean("unified"))
    }
}
