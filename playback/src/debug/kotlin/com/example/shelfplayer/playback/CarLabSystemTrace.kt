package com.example.shelfplayer.playback

import android.os.Trace
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The shell arms Perfetto. The debug app can only signal a predefined trigger and emit app markers. */
internal class CarLabSystemTrace(
    id: Int,
    case: CarLabCase,
    private val start: Instant,
    private val emit: (String) -> Unit = {
        Trace.beginSection(it)
        Trace.endSection()
    },
    private val onIncident: (String) -> Unit = {},
) {
    private val prefix = "BookWave/$id/${case.name}/"
    private val firstIncident = AtomicBoolean(true)

    fun playback(row: String, at: Instant) {
        if (at < start) return
        val kind = row.substringBefore(' ')
        val event = when {
            row.startsWith("route-boundary edge=RouteSafetyPause ") -> "RouteSafetyPause"
            row.startsWith("route-boundary edge=ProjectionDeparture ") -> "ProjectionDeparture"
            row.startsWith("track-progress event=NoFrameProgress ") -> "NoFrameProgress"
            row.startsWith("actual-track ") && ":Speaker " in row -> "actual-speaker"
            row.startsWith("actual-track ") && ":RemoteSubmix " in row -> "actual-projection-mix"
            kind in playbackEvents -> kind
            else -> return
        }
        mark(event)
        if (event in incidentEvents) incident(event)
    }

    /** Callers supply fixed suite events or enum names only. Never trace raw diagnostic fields. */
    fun mark(event: String) = emit(prefix + event)

    fun incident(event: String) {
        if (firstIncident.compareAndSet(true, false)) onIncident(event)
    }

    companion object {
        private val incidentEvents = setOf(
            "RouteSafetyPause",
            "NoFrameProgress",
            "playback-error",
            "position-compensation",
        )
        private const val START_TRIGGER = "org.homebord.bookwave.debug.car-lab"
        private val playbackEvents = setOf(
            "actual-track", "device-appeared", "device-disappeared", "output-appeared", "output-disappeared",
            "bluetooth-event", "bluetooth-peer", "car-bluetooth-window", "projection-change",
            "car-controller-bound", "car-controller-unbound", "focus-observation", "input-key", "input-transport",
            "playback-intent", "media3-playback", "playback-error", "player-discontinuity", "track-progress",
            "preferred-output-request", "headset-take-back", "position-compensation", "output-proof",
            "car-recovery-outcome", "playback-service-started", "playback-service-stopped", "events-dropped",
        )

        /** A successful exit only confirms trigger delivery. Perfetto doesn't disclose whether a listener was armed. */
        fun signal(incident: Boolean = false): Boolean {
            val name = if (incident) "$START_TRIGGER-incident" else START_TRIGGER
            val process = try {
                ProcessBuilder("/system/bin/trigger_perfetto", name).start()
            } catch (_: IOException) {
                return false
            } catch (_: SecurityException) {
                return false
            }
            return try {
                process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.inputStream.close()
                process.errorStream.close()
                process.outputStream.close()
            }
        }
    }
}
