package com.example.shelfplayer.playback

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.WeakHashMap

/** One drive at a time. Each real player pairs its own sink with its own AudioOutput provider. */
internal class DriveFlushProbe(private val elapsedNs: () -> Long = SystemClock::elapsedRealtimeNanos) {
    @Volatile
    private var active: DriveFlushRecording? = null
    private var enabled = false

    inner class Player {
        @Volatile
        var trackEpoch: Long? = null

        val recording: DriveFlushRecording? get() = active
    }

    /** Recorder lifecycle calls are serialized by AndroidAutoDiagnosticsCapture. */
    fun setEnabled(value: Boolean) {
        enabled = value
        active?.seal()
        active = if (enabled) DriveFlushRecording(elapsedNs) else null
    }

    fun open(changed: () -> Unit): DriveFlushRecording = (active ?: DriveFlushRecording(elapsedNs)).also {
        it.begin(changed)
        active = it
    }

    fun close(recording: DriveFlushRecording) {
        recording.seal()
        if (active === recording) active = if (enabled) DriveFlushRecording(elapsedNs) else null
    }
}

internal class DriveFlushRecording(private val elapsedNs: () -> Long) {
    private data class Write(
        val atNs: Long,
        val epoch: Long,
        val format: AudioOutputWriteTrace.Format,
        val acceptedBytes: Int,
        val level: PcmWriteLevel,
    ) {
        fun json(flushNs: Long): JSONObject = JSONObject()
            .put("elapsedRealtimeNs", atNs).put("ageNs", flushNs - atNs)
            .put("encoding", format.encoding).put("sampleRateHz", format.sampleRate)
            .put("pcmFrameBytes", format.pcmFrameBytes ?: JSONObject.NULL).put("acceptedBytes", acceptedBytes)
            .put("status", level.status).put("sampleCount", level.sampleCount)
            .put("rmsDbfs", level.rmsDbfs ?: JSONObject.NULL).put("peakDbfs", level.peakDbfs ?: JSONObject.NULL)
    }

    private data class Flush(
        val atNs: Long,
        val departure: Boolean,
        val epoch: Long?,
        val callers: List<String>,
        val writes: List<Write>,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("elapsedRealtimeNs", atNs).put("phase", if (departure) "departure" else "drive")
            .put("trackEpoch", epoch ?: JSONObject.NULL).put("callerStack", JSONArray(callers))
            .put("precedingWrites", JSONArray(writes.map { it.json(atNs) }))
    }

    private val recent = WeakHashMap<DriveFlushProbe.Player, ArrayDeque<Write>>()
    private val flushes = mutableListOf<Flush>()
    private var departing = false
    private var sealed = false
    private var changed: (() -> Unit)? = null

    @Synchronized
    fun begin(onChange: () -> Unit) {
        val cutoff = elapsedNs() - PRE_ROLL_NS
        recent.values.forEach { writes ->
            while (writes.isNotEmpty() &&
                writes.first().atNs < cutoff
            ) {
                writes.removeFirst()
            }
        }
        changed = onChange
    }

    @Synchronized
    fun accepted(
        player: DriveFlushProbe.Player,
        epoch: Long,
        format: AudioOutputWriteTrace.Format,
        buffer: ByteBuffer,
        offset: Int,
        count: Int,
    ) {
        if (sealed || count <= 0 || player.trackEpoch != epoch) return
        val atNs = elapsedNs()
        val writes = recent.getOrPut(player) { ArrayDeque() }
        if (writes.lastOrNull()?.epoch != epoch) writes.clear()
        if (writes.size == MAX_WRITES) writes.removeFirst()
        writes.addLast(Write(atNs, epoch, format, count, PcmWriteLevel.measure(format, buffer, offset, count)))
    }

    @Synchronized
    fun departure() {
        departing = true
    }

    fun flush(player: DriveFlushProbe.Player) {
        val notify = synchronized(this) {
            if (sealed) return
            val atNs = elapsedNs()
            val writes = recent.remove(player)
            val onChange = changed ?: return
            if (flushes.isNotEmpty() && !departing) return
            val epoch = player.trackEpoch
            val preceding = writes?.filter { it.epoch == epoch }.orEmpty()
            flushes += Flush(atNs, departing, epoch, callers(), preceding)
            if (departing) seal()
            onChange
        }
        notify()
    }

    @Synchronized
    fun seal() {
        sealed = true
        changed = null
        recent.clear()
    }

    fun snapshot(): JSONArray = JSONArray(synchronized(this) { flushes.toList() }.map(Flush::json))

    /** Only code symbols above the instrumented method, nearest caller first. */
    private fun callers() = Thread.currentThread().stackTrace.asSequence()
        .dropWhile { it.className != DiagnosticAudioSink::class.java.name || it.methodName != "flush" }
        .drop(1)
        .filter { it.className.startsWith("androidx.media3.") || it.className.startsWith("com.example.shelfplayer.") }
        .take(MAX_CALLERS)
        .map { "${it.className}.${it.methodName}" }
        .toList()

    companion object {
        private const val MAX_WRITES = 8
        private const val MAX_CALLERS = 12
        private const val PRE_ROLL_NS = 1_000_000_000L
    }
}
