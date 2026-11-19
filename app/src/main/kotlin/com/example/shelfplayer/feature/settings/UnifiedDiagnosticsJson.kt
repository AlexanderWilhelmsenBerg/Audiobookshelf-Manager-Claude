package com.example.shelfplayer.feature.settings

import com.example.shelfplayer.core.common.log.LoggedEvent
import com.example.shelfplayer.playback.AudioRouteLabState
import com.example.shelfplayer.playback.OutputClassifierState
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** The playback report contains one timeline and the latest redacted diagnostic snapshots. */
internal object UnifiedDiagnosticsJson {
    data class Environment(val androidApi: Int, val targetApi: Int)
    data class Snapshot(
        val recording: Boolean,
        val lab: AudioRouteLabState,
        val classifier: OutputClassifierState,
        val events: List<LoggedEvent>,
        val previousCrash: String?,
    )

    fun build(build: String, environment: Environment, snapshot: Snapshot): String = JSONObject().apply {
        put("schema", "bookwave.diagnostics")
        put("schemaVersion", 2)
        put("generatedAtUtc", Instant.now().toString())
        put("build", build)
        put("androidApi", environment.androidApi)
        put("targetApi", environment.targetApi)
        put("recording", snapshot.recording)
        put("current", current(snapshot.lab, snapshot.classifier))
        put("timeline", timeline(snapshot.lab.entries, snapshot.classifier.entries))
        put("firstIncident", timeline(snapshot.lab.stallIncident, emptyList()))
        put("problems", problems(snapshot.events))
        snapshot.previousCrash?.let { put("previousCrash", it) }
        put(
            "privacy",
            "Opt-in playback capture. Output aliases do not identify hardware. " +
                "A route observation does not measure heard sound.",
        )
    }.toString(2)

    private fun current(lab: AudioRouteLabState, classifier: OutputClassifierState): JSONObject = JSONObject()
        .put("track", lab.reportStatus)
        .put("outputPolicy", lab.route)
        .put("inputStatus", lab.inputStatus)
        .put("inputs", JSONArray(lab.inputDevices))
        .put("outputStatus", classifier.status)
        .put("outputs", JSONArray(classifier.current))

    private data class Row(val at: Instant, val source: String, val kind: String, val details: String) {
        fun json(): JSONObject = JSONObject()
            .put("atUtc", at.toString())
            .put("source", source)
            .put("event", kind)
            .put("details", details)
    }

    private fun timeline(playback: List<String>, system: List<String>): JSONArray {
        val sequence = playback.mapNotNull { row("playback", it) } + system.mapNotNull { row("system", it) }
        return JSONArray(sequence.distinct().sortedBy(Row::at).map(Row::json))
    }

    private fun row(source: String, line: String): Row? {
        val at = runCatching { Instant.parse(line.substringBefore(' ')) }.getOrNull() ?: return null
        val body = line.substringAfter(' ', "")
        val kind = body.substringBefore(' ').substringBefore('=')
        return Row(at, source, kind, body.drop(kind.length).removePrefix("=").trimStart())
    }

    private fun problems(events: List<LoggedEvent>): JSONArray = JSONArray(
        events.filter(LoggedEvent::isProblem).takeLast(MAX_PROBLEMS).distinct().map { row ->
            JSONObject()
                .put("atUtc", row.at.toString())
                .put("level", row.level.name)
                .put("tag", row.tag)
                .put("details", row.line)
        },
    )

    private const val MAX_PROBLEMS = 40
}
