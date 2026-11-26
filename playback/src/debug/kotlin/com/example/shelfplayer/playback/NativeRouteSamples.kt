package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Platform handles stay inside this run. The same alias is used on both sides even after inventory removal. */
internal data class RoutePort(val handle: Int, val type: Int)

internal data class RouteReading(val status: String, val outputs: List<RoutePort> = emptyList()) {
    companion object {
        fun devices(outputs: List<RoutePort>) = RouteReading("available", outputs.sortedBy { it.handle })
    }
}

internal data class NativeRoutePair(val preferred: RouteReading, val actual: RouteReading) {
    companion object {
        val NoTrack = NativeRoutePair(RouteReading("no-track"), RouteReading("no-track"))

        fun read(track: AudioTrack?): NativeRoutePair {
            if (track == null) return NoTrack
            val preferred = reading {
                track.preferredDevice?.let { RouteReading.devices(listOf(it.port())) } ?: RouteReading("default")
            }
            val actual = reading {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return@reading RouteReading("not-playing")
                val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    track.routedDevices
                } else {
                    listOfNotNull(track.routedDevice)
                }
                if (devices.isEmpty()) RouteReading("unreported") else RouteReading.devices(devices.map { it.port() })
            }
            return NativeRoutePair(preferred, actual)
        }

        private fun AudioDeviceInfo.port() = RoutePort(id, type)

        private inline fun reading(query: () -> RouteReading): RouteReading = try {
            query()
        } catch (_: RuntimeException) {
            RouteReading("query-failed")
        }
    }
}

/** Serialize observations across the renderer and routing callbacks. No JSON or disk work on either thread. */
internal class NativeRouteSamples(private val elapsedNs: () -> Long = SystemClock::elapsedRealtimeNanos) {
    private val aliases = mutableMapOf<RoutePort, Int>()
    private val pending = ArrayDeque<Sample>()
    private var sequence = 0L
    private var last: Sample? = null
    private var closed = false

    internal data class Sample(
        val sequence: Long,
        val startNs: Long,
        val endNs: Long,
        val epoch: Long?,
        val source: String,
        val pair: NativeRoutePair,
        val change: String,
    )

    @Synchronized
    fun observe(source: String, epoch: Long?, read: () -> NativeRoutePair, isCurrent: () -> Boolean) {
        if (closed || !isCurrent()) return
        val start = elapsedNs()
        val pair = read()
        val end = elapsedNs()
        if (!isCurrent()) return
        val previous = last
        val change = when {
            previous == null -> "baseline"
            epoch != previous.epoch -> "track-changed"
            pair == previous.pair -> "unchanged"
            pair.preferred == previous.pair.preferred -> "actual"
            pair.actual == previous.pair.actual -> "preferred"
            else -> "both"
        }
        if (source == "poll" && change == "unchanged") return
        val sample = Sample(++sequence, start, end, epoch, source, pair, change)
        last = sample
        if (pending.size >= MAX_ROWS) pending.removeAt(1)
        pending.addLast(sample)
    }

    /** Drained from the coordinator thread. Sequence gaps expose dropped records even after a restart. */
    fun drain(close: Boolean = false): List<JSONObject> {
        val batch = synchronized(this) {
            if (close) closed = true
            pending.toList().also { pending.clear() }
        }
        return batch.map { row ->
            JSONObject().put("sequence", row.sequence).put("elapsedRealtimeNs", row.startNs)
                .put("readEndElapsedRealtimeNs", row.endNs).put("trackEpoch", row.epoch ?: JSONObject.NULL)
                .put("source", row.source).put("change", row.change)
                .put("preferred", outputJson(row.pair.preferred)).put("actual", outputJson(row.pair.actual))
        }
    }

    private fun outputJson(reading: RouteReading): JSONObject {
        val devices = reading.outputs.map { port ->
            val alias = aliases[port] ?: if (aliases.size < MAX_ROWS) {
                (aliases.size + 1).also { aliases[port] = it }
            } else {
                null
            }
            JSONObject().put("alias", alias?.let { "out$it" } ?: "unmapped")
                .put("androidType", port.type).put("role", AudioTrackRouteTypes.label(port.type))
        }
        return JSONObject().put("status", reading.status).put("outputs", JSONArray(devices))
    }

    companion object {
        const val MAX_ROWS = 256
    }
}
