package com.example.shelfplayer.playback

import android.os.Handler
import com.example.shelfplayer.core.common.time.AppClock

internal enum class AudioTimingBoundary {
    RouteSafetyPause,
    ProjectionDeparture,
}

/** Position that Media3's output returned during normal playback, with the time it was observed. */
internal data class AudioTrackTimingSample(
    val observerEpoch: Long,
    val monotonicNs: Long,
    val outputPositionUs: Long?,
) {
    fun evidence(): String = "track-timing epoch=$observerEpoch monotonicNs=$monotonicNs " +
        "outputPositionUs=${outputPositionUs ?: "null"}"
}

/** Each Media3 output writes only its own clock; retiring an old output cannot clear its successor. */
internal class ObservedOutputClock(val epoch: Long, private val clock: AppClock, private val enabled: () -> Boolean) {
    @Volatile
    var latest: AudioTrackTimingSample? = null
        private set

    fun observe(positionUs: Long?) {
        latest = if (positionUs != null && enabled()) {
            AudioTrackTimingSample(epoch, clock.elapsed().inWholeNanoseconds, positionUs.takeIf { it >= 0 })
        } else {
            null
        }
    }
}

/**
 * A bounded diagnostic burst, never a route/clock authority. All scheduling state lives on [handler].
 * Routing callback storms cannot extend a burst. Timers only copy an existing output-clock observation.
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
    private var lastRecorded: AudioTrackTimingSample? = null

    fun request(epoch: Long, current: () -> Boolean, read: () -> AudioTrackTimingSample?) {
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
                    if (evidence != null && evidence !== lastRecorded && permitted(current)) {
                        lastRecorded = evidence
                        record(evidence)
                    }
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
        const val SAMPLE_COUNT = 120
        const val INTERVAL_MS = 250L
        const val COOLDOWN_MS = 35_000L
    }
}
