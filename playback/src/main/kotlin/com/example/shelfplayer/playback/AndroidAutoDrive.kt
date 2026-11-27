package com.example.shelfplayer.playback

import com.example.shelfplayer.core.common.time.AppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

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

/** Boundaries and native callbacks record routes. The timed samples copy clocks already returned to Media3. */
internal class DriveRouteTrace(
    private val monitor: AudioTrackRouteMonitor,
    private val scope: CoroutineScope,
    private val clock: AppClock,
    private val changed: () -> Unit,
) {
    private val samples = NativeRouteSamples(keepOpening = OPENING_ROWS)
    private val observation = monitor.observeRoutes(samples, changed)
    private val flushProbe = monitor.driveFlush
    private val flushes = flushProbe.open(changed)
    private var burst: Job? = null
    private val positions = ArrayDeque<AudioTrackTimingSample>()
    private var closed = false

    fun boundary(source: String, durationMs: Long) {
        burst?.cancel()
        val startNs = clock.elapsed().inWholeNanoseconds
        val window = startNs..(startNs + durationMs.milliseconds.inWholeNanoseconds)
        observation.sample(source)
        burst = scope.launch {
            repeat((durationMs / INTERVAL_MS).toInt()) {
                delay(INTERVAL_MS)
                val sample = monitor.latestOutputPosition()
                if (sample != null && sample.monotonicNs in window && retain(sample)) {
                    changed()
                }
            }
        }
    }

    @Synchronized
    private fun retain(sample: AudioTrackTimingSample): Boolean {
        if (closed || sample == positions.lastOrNull()) return false
        if (positions.size == MAX_POSITIONS) positions.removeFirst()
        positions.addLast(sample)
        return true
    }

    fun close() {
        synchronized(this) { closed = true }
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
        val clocks = synchronized(this) { positions.toList() }
        row.put(
            "media3OutputClockSamples",
            JSONArray(
                clocks.map { sample ->
                    JSONObject().put("trackEpoch", sample.observerEpoch).put("monotonicNs", sample.monotonicNs)
                        .put("outputPositionUs", sample.outputPositionUs ?: JSONObject.NULL)
                },
            ),
        )
    }

    companion object {
        const val ARRIVAL_MS = 10_000L
        const val DEPARTURE_MS = 5_000L
        private const val INTERVAL_MS = 200L
        private const val MAX_POSITIONS = 128
        private const val OPENING_ROWS = 96
    }
}
