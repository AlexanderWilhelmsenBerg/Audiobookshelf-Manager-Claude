package com.example.shelfplayer.playback

import com.example.shelfplayer.core.common.log.LoggedEvent
import com.example.shelfplayer.core.model.playback.AudioOutput
import java.time.Instant
import kotlin.time.Duration.Companion.hours

/**
 * Privacy allow-list: never persist arbitrary rendered log fields. Media titles can be included in
 * user-opted-in normal diagnostics, so copying the full EventLog into a durable trace is unsafe.
 */
internal object AudioRouteLabEvidence {
    private val allowed = mapOf(
        "MediaSession hardware media button observed" to "hardware-button",
        "MediaSession transport request observed" to "transport-intent",
        "Media3 audible playback changed" to "media3-playback",
        "Android Auto projection state changed" to "projection-change",
        "A car connected to the media session" to "car-controller-bound",
        "Car lifecycle continuity decision" to "continuity-decision",
        "Car lifecycle route recovery changed" to "recovery-step",
        "Playback was asked to change playWhenReady" to "playback-intent",
        "The audio output was set" to "preferred-output-request",
        "The car output actions were recomputed" to "route-policy-updated",
        "A car controller disconnected from the media session" to "car-controller-unbound",
        "Android Auto Play selected the car audio route" to "car-route-intent",
        "MediaSession controller transport batch completed" to "transport-batch",
        "Muted output route proof completed" to "output-proof",
        "Muted output position compensated" to "position-compensation",
        "Muted output verification refused excessive post-seek clock movement" to "position-refusal",
    )
    private val safeFields = setOf(
        "keyCode", "keyAction", "keyInputId", "keyInputSource", "inputOrigin",
        "headsetNameMatches", "requested", "controllerKind", "reason", "phase",
        "status", "kind", "event", "source", "projection", "isPlaying", "playWhenReady",
        "routeKnown", "onHeadset", "onCar", "carBound", "routedTo", "honoured",
        "generation", "selectionSequence", "commandGeneration", "confirmation", "destination", "advance",
        "finalDeparture", "carStillConnected", "controllerIsCar",
        "proofEpoch", "requestGeneration", "requestSelection",
    )
    private const val MAX_SAFE_FIELDS = 20
    private const val FUTURE_SKEW_SECONDS = 60L

    private val token = Regex("""\b([a-zA-Z][a-zA-Z0-9]*)=([a-zA-Z0-9_-]+)\b""")

    fun event(event: LoggedEvent): String? {
        if (event.tag != "AndroidAuto" && event.tag != "Playback") return null
        val category = allowed.entries.firstOrNull { event.line.startsWith(it.key) }?.value ?: return null
        val attributes = token.findAll(event.line)
            .filter { it.groupValues[1] in safeFields }
            .take(MAX_SAFE_FIELDS)
            .joinToString(" ") { "${it.groupValues[1]}=${it.groupValues[2]}" }
        return if (attributes.isEmpty()) category else "$category $attributes"
    }

    /** Per-record TTL even when a new event keeps the incident file's modification time fresh. */
    fun recent(records: List<String>, now: Instant, maxRows: Int): List<String> {
        val cutoff = now.minusSeconds(24.hours.inWholeSeconds)
        return records.asSequence()
            .filter { record ->
                val at = runCatching { Instant.parse(record.substringBefore(' ')) }.getOrNull()
                at != null && !at.isBefore(cutoff) && !at.isAfter(now.plusSeconds(FUTURE_SKEW_SECONDS))
            }
            .toList()
            .takeLast(maxRows)
    }

    /** Identifiers and display names must never appear in an export. */
    fun route(outputs: List<AudioOutput>, selectedId: String?): String {
        val connected = outputs.groupingBy { it.role.name }.eachCount()
            .toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "none" }
        val policyActive = outputs.filter(AudioOutput::isActive)
            .groupingBy { it.role.name }.eachCount()
            .toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "none" }
        val selected = outputs.firstOrNull { it.id == selectedId }?.role?.name
            ?: if (selectedId == null) "Automatic" else "Disconnected"
        return "selection=$selected connected=[$connected] policyActive=[$policyActive] actualTrack=unavailable"
    }
}
