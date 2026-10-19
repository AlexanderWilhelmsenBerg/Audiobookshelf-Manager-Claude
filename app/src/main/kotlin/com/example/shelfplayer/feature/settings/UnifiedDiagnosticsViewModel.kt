package com.example.shelfplayer.feature.settings

import androidx.lifecycle.ViewModel
import com.example.shelfplayer.core.common.log.EventLog
import com.example.shelfplayer.playback.AudioRouteLab
import com.example.shelfplayer.playback.OutputClassifier
import com.example.shelfplayer.playback.UnifiedDiagnosticsCapture
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject

/**
 * Structured diagnostics are assembled only when the user explicitly copies the report.
 * All sources are existing allow-listed or already-redacted in-memory diagnostic sinks.
 * No names, addresses, media identifiers or server state are fetched for the report.
 */
@HiltViewModel
class UnifiedDiagnosticsViewModel @Inject constructor(
    private val capture: UnifiedDiagnosticsCapture,
    private val lab: AudioRouteLab,
    private val classifier: OutputClassifier,
    private val logs: EventLog,
) : ViewModel() {
    val active: StateFlow<Boolean> = capture.active
    val labState = lab.state
    val classifierState = classifier.state
    val raceState = logs.raceCapture

    fun start() = capture.start()
    fun stop() = capture.stop()
    fun clear() = capture.clear()

    fun jsonReport(build: String, debugConsoleReport: String): String {
        val labSnapshot = lab.state.value
        val classifierSnapshot = classifier.state.value
        val output = JSONObject()
        output.put("schema", "bookwave.diagnostics")
        output.put("schemaVersion", 1)
        output.put("generatedAtUtc", Instant.now().toString())
        output.put("build", build)
        output.put("privacy", "Opt-in. Redacted logs, session-only output aliases. No raw device names, addresses or book data.")
        output.put(
            "recording",
            JSONObject()
                .put("unified", capture.active.value)
                .put("audioRouteLab", labSnapshot.recording)
                .put("outputClassifier", classifierSnapshot.recording)
                .put("eventRaceCapture", logs.raceCapture.value.recording),
        )
        output.put(
            "audioRouteLab",
            JSONObject()
                .put("routePolicy", labSnapshot.route)
                .put("actualTrack", labSnapshot.reportStatus)
                .put("events", JSONArray(labSnapshot.entries)),
        )
        output.put(
            "outputClassifier",
            JSONObject()
                .put("status", classifierSnapshot.status)
                .put("current", JSONArray(classifierSnapshot.current))
                .put("events", JSONArray(classifierSnapshot.entries)),
        )
        output.put(
            "eventLog",
            JSONArray().apply {
                logs.events.value.forEach { row ->
                    put(
                        JSONObject()
                            .put("atUtc", row.at.toString())
                            .put("level", row.level.name)
                            .put("tag", row.tag)
                            .put("line", row.line),
                    )
                }
            },
        )
        output.put("playbackRace", JSONArray(logs.raceCapture.value.lines))
        output.put("debugConsole", debugConsoleReport)
        output.put("limitations", JSONArray(listOf(
            "Android route prediction does not prove human-heard audio.",
            "Input device -1 cannot identify headset A or B.",
            "AudioTrack observation requires recording to be enabled before Media3 player creation.",
            "Event log and classifier are bounded and session-local; route-lab history uses private 24-hour retention.",
        )))
        return output.toString(2)
    }
}
