package com.example.shelfplayer.feature.settings

import com.example.shelfplayer.core.common.log.LoggedEvent
import com.example.shelfplayer.core.common.log.RaceCaptureState
import com.example.shelfplayer.playback.AudioRouteLabState
import com.example.shelfplayer.playback.OutputClassifierState
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Pure serializer for one copyable, bounded and typed JSON diagnostic report.
 * The callers provide existing privacy-scrubbed sink rows; no Android service,
 * server, Bluetooth product name or personal library metadata is queried here.
 */
internal object UnifiedDiagnosticsJson {
    @Suppress("LongParameterList")
    fun build(
        build: String,
        active: Boolean,
        lab: AudioRouteLabState,
        classifier: OutputClassifierState,
        eventLog: List<LoggedEvent>,
        race: RaceCaptureState,
        debugConsole: String,
    ): String = JSONObject().apply {
        put("schema", "bookwave.diagnostics")
        put("schemaVersion", 1)
        put("generatedAtUtc", Instant.now().toString())
        put("build", build)
        put(
            "privacy",
            "Opt-in. Redacted logs, session-only output and capture-local input aliases. " +
                "No raw device names, addresses, descriptors or book data.",
        )
        put(
            "recording",
            JSONObject()
                .put("unified", active)
                .put("audioRouteLab", lab.recording)
                .put("outputClassifier", classifier.recording)
                .put("eventRaceCapture", race.recording),
        )
        put(
            "audioRouteLab",
            JSONObject()
                .put("routePolicy", lab.route)
                .put("actualTrack", lab.reportStatus)
                .put("inputStatus", lab.inputStatus)
                .put("inputDevices", JSONArray(lab.inputDevices))
                .put("events", JSONArray(lab.entries)),
        )
        put(
            "outputClassifier",
            JSONObject()
                .put("status", classifier.status)
                .put("current", JSONArray(classifier.current))
                .put("events", JSONArray(classifier.entries)),
        )
        put(
            "eventLog",
            JSONArray().apply {
                eventLog.forEach { row ->
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
        put("playbackRace", JSONArray(race.lines))
        put("debugConsole", debugConsole)
        put("limitations", limitations())
    }.toString(2)

    private fun limitations(): JSONArray = JSONArray(
        listOf(
            "Android route prediction does not prove human-heard audio.",
            "Input device -1 cannot identify headset A or B.",
            "Input descriptor aliases apply only within one opt-in capture; " +
                "they do not identify an audio output.",
            "Controller software identity and RemoteSubmix do not prove a physical button or car speaker.",
            "AudioTrack observation requires recording before Media3 player creation.",
            "Event log and classifier are bounded/session-local; route lab retains at most 24 hours.",
        ),
    )
}
