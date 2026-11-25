package com.example.shelfplayer.playback

import androidx.media3.common.C
import com.example.shelfplayer.core.common.time.AppClock
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal enum class PlaybackPositionWrite {
    RouteCheckpoint,
    SessionSeek,
    SessionQueue,
    QueueInstall,
    ResumeFreshness,
    DecoderRecovery,
    NotificationSkip,
    AutoRewind,
    SleepTimerRewind,
}

/** In-memory pre-roll and bounded post-roll. Playback never writes diagnostic files. */
internal class AudioOutputWriteTrace(private val clock: AppClock) {
    class Capture(val at: Instant, val atNs: Long)

    enum class ClockStage { AudioOutput, Media3Sink }

    /** One sampler per audio boundary, called by its Media3 playback thread. */
    inner class ClockSampler(private val stage: ClockStage, private val epoch: Long? = null) {
        private var previousCapture: Capture? = null
        private var lastNs = 0L
        private var hadPosition = false

        fun sample(capture: Capture, positionUs: Long) {
            val atNs = nowNs()
            val position = positionUs.takeUnless { it == Long.MIN_VALUE || it == C.TIME_UNSET }
            val hasPosition = position != null
            if (capture === previousCapture && hasPosition == hadPosition && atNs - lastNs < CLOCK_INTERVAL_NS) {
                return
            }
            previousCapture = capture
            lastNs = atNs
            hadPosition = hasPosition
            clockSample(capture, epoch, atNs, stage, position)
        }
    }

    data class Format(val encoding: Int, val sampleRate: Int, val pcmFrameBytes: Int?)

    data class Write(
        val format: Format,
        val ptsUs: Long,
        val offsetBytes: Int,
        val acceptedBytes: Int,
        val completed: Boolean,
    ) {
        fun fields(): String {
            val frames = format.pcmFrameBytes?.takeIf { acceptedBytes % it == 0 }?.let { acceptedBytes / it }
            val pts = ptsUs.takeUnless { it == C.TIME_UNSET || it == C.TIME_END_OF_SOURCE }?.toString() ?: "null"
            return "ptsUs=$pts offsetBytes=$offsetBytes acceptedBytes=$acceptedBytes " +
                "acceptedFrames=${frames?.toString() ?: "null"} complete=$completed " +
                "encoding=${format.encoding} sampleRate=${format.sampleRate} " +
                "pcmFrameBytes=${format.pcmFrameBytes?.toString() ?: "null"}"
        }
    }

    private class Row(
        val capture: Capture,
        val atNs: Long,
        val trackEpoch: Long?,
        val kind: String,
        val write: Write? = null,
        val fields: String = "",
    ) {
        fun line(): String {
            val at = capture.at.plusNanos(atNs - capture.atNs)
            val details = write?.fields() ?: fields
            return "$at $kind monotonicNs=$atNs trackEpoch=${trackEpoch?.toString() ?: "null"} $details".trimEnd()
        }
    }

    private class Window(val capture: Capture, val endNs: Long, val rows: MutableList<Row>) {
        fun includes(row: Row): Boolean = row.capture === capture && row.atNs <= endNs
    }

    @Volatile
    var active: Capture? = null
        private set

    private val rolling = ArrayDeque<Row>()
    private val windows = ArrayDeque<Window>()

    @Synchronized
    fun start() {
        rolling.clear()
        active = Capture(clock.now(), nowNs())
    }

    @Synchronized
    fun stop() {
        active = null
        rolling.clear()
    }

    @Synchronized
    fun clear(keepRecording: Boolean) {
        stop()
        windows.clear()
        if (keepRecording) start()
    }

    @Synchronized
    fun write(capture: Capture, epoch: Long, write: Write) {
        if (active !== capture) return
        add(Row(capture, nowNs(), epoch, "audio-write", write))
    }

    @Synchronized
    fun outputEvent(capture: Capture, epoch: Long?, kind: String) {
        if (active !== capture) return
        add(Row(capture, nowNs(), epoch, kind))
    }

    @Synchronized
    private fun clockSample(capture: Capture, epoch: Long?, atNs: Long, stage: ClockStage, positionUs: Long?) {
        if (active !== capture) return
        val position = positionUs?.toString() ?: "null"
        add(Row(capture, atNs, epoch, "audio-clock", fields = "stage=$stage positionUs=$position"))
    }

    fun positionWrite(stage: PlaybackPositionWrite, targetMs: Long) {
        val capture = active ?: return
        positionWrite(capture, stage, targetMs)
    }

    @Synchronized
    private fun positionWrite(capture: Capture, stage: PlaybackPositionWrite, targetMs: Long) {
        if (active !== capture) return
        val target = targetMs.takeUnless { it == C.TIME_UNSET }?.toString() ?: "null"
        add(Row(capture, nowNs(), null, "position-action", fields = "stage=$stage targetMs=$target"))
    }

    @Synchronized
    fun nativeRouteCallback(epoch: Long, measuredRoute: String, changed: Boolean) {
        val capture = active ?: return
        val atNs = nowNs()
        add(Row(capture, atNs, epoch, "audio-output-route-callback", fields = "trackRoute=$measuredRoute"))
        if (changed) mark("NativeRouteChange", epoch, atNs)
    }

    @Synchronized
    fun mark(trigger: String, epoch: Long?, atNs: Long = nowNs()) {
        val capture = active ?: return
        val marker = Row(capture, atNs, epoch, "audio-write-boundary", fields = "trigger=$trigger")
        add(marker)
        if (windows.lastOrNull()?.includes(marker) == true) return
        if (windows.size == MAX_WINDOWS) windows.removeFirst()
        windows.addLast(Window(capture, atNs + POST_ROLL_NS, rolling.toMutableList()))
    }

    private fun add(row: Row) {
        while (rolling.isNotEmpty() &&
            (rolling.size >= MAX_PRE_ROWS || row.atNs - rolling.first().atNs > PRE_ROLL_NS)
        ) {
            rolling.removeFirst()
        }
        rolling.addLast(row)
        windows.lastOrNull()?.let { window ->
            if (window.includes(row) && window.rows.size < MAX_WINDOW_ROWS) window.rows.add(row)
        }
    }

    fun entries(): List<String> {
        val cutoff = clock.now().minusSeconds(24.hours.inWholeSeconds)
        return snapshot().distinct()
            .filter { it.capture.at.plusNanos(it.atNs - it.capture.atNs) >= cutoff }
            .map(Row::line)
    }

    @Synchronized
    private fun snapshot(): List<Row> = windows.flatMap(Window::rows)

    private fun nowNs(): Long = clock.elapsed().inWholeNanoseconds

    private companion object {
        val CLOCK_INTERVAL_NS = 100.milliseconds.inWholeNanoseconds
        val PRE_ROLL_NS = 6.seconds.inWholeNanoseconds
        val POST_ROLL_NS = 2.seconds.inWholeNanoseconds
        const val MAX_PRE_ROWS = 768
        const val MAX_WINDOW_ROWS = 1_024
        const val MAX_WINDOWS = 6
    }
}
