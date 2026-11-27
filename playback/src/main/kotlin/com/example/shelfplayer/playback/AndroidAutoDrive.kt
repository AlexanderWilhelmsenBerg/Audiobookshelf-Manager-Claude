package com.example.shelfplayer.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** A stable capture number identifies a row even as new drives arrive or the picker is filtered. */
data class AndroidAutoDrive(
    val number: Long,
    val started: Instant,
    val connected: Boolean,
    val ended: Instant?,
    val endReason: String?,
    val headsetPresent: Boolean?,
    val observingDeparture: Boolean,
) {
    internal companion object {
        fun from(row: JSONObject) = AndroidAutoDrive(
            row.getLong("attempt"),
            Instant.parse(row.getString("startedAtUtc")),
            !row.isNull("connectedAtUtc"),
            row.optString("endedAtUtc").takeUnless { row.isNull("endedAtUtc") }?.let(Instant::parse),
            row.optString("endReason").takeUnless { row.isNull("endReason") },
            row.opt("headsetPresentAtConnect") as? Boolean,
            row.optString("departureObservation") == "recording",
        )
    }
}

/** Native callbacks cover the drive. Only arrival and departure warrant a short burst of frame-clock samples. */
internal class DriveRouteTrace(
    monitor: AudioTrackRouteMonitor,
    private val scope: CoroutineScope,
    changed: () -> Unit,
) {
    private val samples = NativeRouteSamples(keepOpening = OPENING_ROWS)
    private val observation = monitor.observeRoutes(samples, changed)
    private val flushProbe = monitor.driveFlush
    private val flushes = flushProbe.open(changed)
    private var burst: Job? = null

    fun boundary(source: String, durationMs: Long) {
        burst?.cancel()
        observation.sample(source)
        burst = scope.launch {
            repeat((durationMs / INTERVAL_MS).toInt()) {
                delay(INTERVAL_MS)
                observation.sample("boundary-clock")
            }
        }
    }

    fun close() {
        flushProbe.close(flushes)
        burst?.cancel()
        observation.close()
    }

    fun departure() {
        flushes.departure()
        boundary("car-disconnected", DEPARTURE_MS)
    }

    fun appendTo(row: JSONObject) {
        row.put("audioSinkFlushes", flushes.snapshot())
        val reads = samples.snapshot()
        row.put("nativeRouteSamples", JSONArray(reads))
        row.put("discardedNativeRouteSamples", (reads.lastOrNull()?.getLong("sequence") ?: 0L) - reads.size)
    }

    companion object {
        const val ARRIVAL_MS = 10_000L
        const val DEPARTURE_MS = 5_000L
        private const val INTERVAL_MS = 200L
        private const val OPENING_ROWS = 96
    }
}
