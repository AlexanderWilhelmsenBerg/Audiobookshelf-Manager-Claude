package com.example.shelfplayer.playback

import java.time.Instant

/** First incident context plus a bounded recent tail. Inputs are existing privacy-safe probe rows. */
internal object AudioStallIncidentEvidence {
    const val MAX_ROWS = 128
    private const val CONTEXT_ROWS = 64
    private const val SYSTEM_ROWS = 24
    private const val RAW_SAMPLES = 2
    private const val TAIL_ROWS = 32
    private const val PREFIX = "stall-incident "
    private val important = setOf(
        "track-progress", "playback-error", "headset-take-back", "car-bluetooth-window",
        "continuity-decision", "recovery-step", "arrival-settle", "output-proof", "playback-intent", "transport-intent",
        "focus-observation", "preferred-output-request", "output-observation", "transfer-stage",
        "projection-change", "car-controller-bound", "car-controller-unbound",
        "observer-audio-track-attached", "observer-audio-track-released-or-replaced",
    )

    fun isPinned(row: String): Boolean = row.substringAfter(' ').startsWith(PREFIX)

    fun isTrigger(value: String): Boolean = value.startsWith("track-progress event=NoFrameProgress ") ||
        (value.startsWith("playback-error ") && "StuckType=" in value)

    fun retain(
        current: AudioRouteLabState,
        system: List<String>,
        row: String,
        trigger: Boolean,
        now: Instant,
    ): List<String> {
        val pinned = AudioRouteLabEvidence.recent(current.stallIncident, now, MAX_ROWS)
        return if (trigger && pinned.isEmpty()) {
            capture(
                current.entries +
                    listOf("$now route-policy ${current.route}", "$now actual-track ${current.reportStatus}"),
                system,
                row,
            )
        } else {
            append(pinned, row)
        }
    }

    fun capture(rows: List<String>, system: List<String>, trigger: String): List<String> = (
        rows.filter { isImportant(it) || category(it) in CONTEXT_ONLY }.takeLast(CONTEXT_ROWS) +
            rows.filter { category(it) == "track-timing" }.takeLast(RAW_SAMPLES) +
            system.takeLast(SYSTEM_ROWS) + trigger
        )
        .map { pin(it, "context") }

    fun append(pinned: List<String>, row: String): List<String> {
        if (pinned.isEmpty() || !isImportant(row)) return pinned
        val context = pinned.filter { " stall-incident phase=context " in it }
        val tail = pinned.filter { " stall-incident phase=after " in it }
        return (context + (tail + pin(row, "after")).takeLast(TAIL_ROWS)).take(MAX_ROWS)
    }

    private fun category(row: String): String = row.substringAfter(' ').substringBefore(' ')

    private fun isImportant(row: String): Boolean = category(row) in important

    private fun pin(row: String, phase: String): String =
        "${row.substringBefore(' ')} $PREFIX" + "phase=$phase ${row.substringAfter(' ')}"

    private val CONTEXT_ONLY = setOf("route-policy", "actual-track")
}
