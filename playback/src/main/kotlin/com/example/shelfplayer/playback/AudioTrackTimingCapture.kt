package com.example.shelfplayer.playback

import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler

/** Numeric output evidence only. Missing native timestamps/parameters remain unknown. */
internal data class AudioTrackTimingSample(
    val observerEpoch: Long,
    val monotonicNs: Long,
    val playState: Int?,
    val sampleRate: Int?,
    val channels: Int?,
    val encoding: Int?,
    val bufferFrames: Int?,
    val headFrames: Long?,
    val timestampFrames: Long?,
    val timestampNs: Long?,
    val underruns: Int?,
    val nativeSpeedMilli: Int?,
    val nativePitchMilli: Int?,
) {
    fun evidence(): String = "track-timing epoch=$observerEpoch monotonicNs=$monotonicNs " +
        "playState=${playState.measured()} sampleRate=${sampleRate.measured()} " +
        "channels=${channels.measured()} encoding=${encoding.measured()} " +
        "bufferFrames=${bufferFrames.measured()} headFrames=${headFrames.measured()} " +
        "timestampFrames=${timestampFrames.measured()} timestampNs=${timestampNs.measured()} " +
        "underruns=${underruns.measured()} nativeSpeedMilli=${nativeSpeedMilli.measured()} " +
        "nativePitchMilli=${nativePitchMilli.measured()}"

    private fun Number?.measured(): String = this?.toString() ?: "null"

    companion object {
        private const val UNSIGNED_HEAD_MASK = 0xffffffffL
        private const val PARAMETER_SCALE = 1_000

        fun read(track: AudioTrack, epoch: Long, monotonicNs: Long): AudioTrackTimingSample {
            val timestamp = AudioTimestamp().takeIf { runCatching { track.getTimestamp(it) }.getOrDefault(false) }
            val parameters = runCatching { track.playbackParams }.getOrNull()
            return AudioTrackTimingSample(
                epoch,
                monotonicNs,
                runCatching { track.playState }.getOrNull(),
                runCatching { track.sampleRate }.getOrNull(),
                runCatching { track.channelCount }.getOrNull(),
                runCatching { track.audioFormat }.getOrNull(),
                runCatching { track.bufferSizeInFrames }.getOrNull(),
                runCatching { track.playbackHeadPosition.toLong() and UNSIGNED_HEAD_MASK }.getOrNull(),
                timestamp?.framePosition,
                timestamp?.nanoTime,
                runCatching { track.underrunCount }.getOrNull(),
                runCatching { parameters?.speed?.times(PARAMETER_SCALE)?.toInt() }.getOrNull(),
                runCatching { parameters?.pitch?.times(PARAMETER_SCALE)?.toInt() }.getOrNull(),
            )
        }
    }
}

/**
 * A bounded diagnostic burst, never a route/clock authority. All scheduling state lives on [handler].
 * Routing callback storms cannot extend a burst. No native queries happen while recording is off.
 */
internal class AudioTrackTimingCapture(
    private val handler: Handler,
    private val enabled: () -> Boolean,
    private val record: (AudioTrackTimingSample) -> Unit,
    private val nowMs: () -> Long,
) {
    private var activeEpoch: Long? = null
    private var nextBurstMs = 0L
    private var work: Runnable? = null

    fun request(epoch: Long, current: () -> Boolean, read: () -> AudioTrackTimingSample) {
        handler.post {
            if (!permitted(current)) return@post
            if (activeEpoch == epoch && (work != null || nowMs() < nextBurstMs)) return@post
            work?.let(handler::removeCallbacks)
            activeEpoch = epoch
            nextBurstMs = nowMs() + COOLDOWN_MS
            var remaining = SAMPLE_COUNT
            val sample = object : Runnable {
                override fun run() {
                    if (work !== this) return
                    if (!permitted(current)) {
                        work = null
                        return
                    }
                    val evidence = read()
                    if (permitted(current)) record(evidence)
                    remaining--
                    if (remaining > 0 && permitted(current)) {
                        handler.postDelayed(this, INTERVAL_MS)
                    } else {
                        work = null
                    }
                }
            }
            work = sample
            sample.run()
        }
    }

    private fun permitted(current: () -> Boolean): Boolean = enabled() && current()

    /** Old release callbacks cannot cancel a newer output's diagnostic burst. */
    fun retire(epoch: Long) {
        handler.post {
            if (activeEpoch == epoch) {
                work?.let(handler::removeCallbacks)
                work = null
                activeEpoch = null
            }
        }
    }

    private companion object {
        const val SAMPLE_COUNT = 16
        const val INTERVAL_MS = 250L
        const val COOLDOWN_MS = 5_000L
    }
}
