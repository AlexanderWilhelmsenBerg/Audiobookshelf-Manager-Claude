package com.example.shelfplayer.playback

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #36 — pairs the measured Android Auto audio-focus pause with one car arrival.
 *
 * The 2026-09-19 physical drive measured this sequence on the target setup:
 *
 * 1. BookWave was actively playing through the #11-owned headset.
 * 2. Media3 set playWhenReady=false with AUDIO_FOCUS_LOSS.
 * 3. the first Android Auto controller bound two seconds later;
 * 4. #11 still owned and reasserted that same headset.
 *
 * This gate deliberately records only that measured mechanism. A noisy-route pause, a generic system pause,
 * or a merely connected headset is not enough. The pending pause also carries the #11 book generation and
 * output id, so changing books or choosing another output cannot revive stale evidence.
 *
 * Not thread-safe by design: PlaybackService calls it from the player/main-thread boundary.
 */
internal class CarArrivalResumeGate(
    private val pairingWindow: Duration = DEFAULT_PAIRING_WINDOW,
) {
    private data class Pending(
        val pausedAt: Duration,
        val generation: Long,
        val outputId: String,
    )

    private var pending: Pending? = null

    /**
     * Records an AUDIO_FOCUS_LOSS only when #11 says the current book was actually heard in this headset.
     */
    fun onAudioFocusLoss(
        at: Duration,
        heardRoute: RouteHeardOwnership.HeardRoute?,
        headsetId: String?,
    ) {
        pending = heardRoute
            ?.takeIf { heard -> headsetId != null && heard.outputId == headsetId }
            ?.let { heard ->
                Pending(
                    pausedAt = at,
                    generation = heard.generation,
                    outputId = heard.outputId,
                )
            }
    }

    /** Any newer listener/playback event makes the old focus-loss candidate unsafe to act on. */
    fun cancel() {
        pending = null
    }

    /**
     * Consumes one candidate on the first car arrival.
     *
     * An arrival consumes the candidate even when it refuses it. Waiting for a later route callback would
     * turn "the car arrived while this exact ownership was current" into a weaker inference and could let a
     * newer explicit choice be overridden.
     */
    fun takeForCarArrival(
        at: Duration,
        heardRoute: RouteHeardOwnership.HeardRoute?,
        headsetId: String?,
    ): String? {
        val candidate = pending ?: return null
        pending = null

        val age = at - candidate.pausedAt
        if (age < Duration.ZERO || age > pairingWindow) return null
        if (heardRoute?.generation != candidate.generation) return null
        if (heardRoute.outputId != candidate.outputId) return null
        if (headsetId != candidate.outputId) return null

        return candidate.outputId
    }

    private companion object {
        /** The measured pause→first-bind interval was two seconds; six leaves bounded startup jitter. */
        val DEFAULT_PAIRING_WINDOW: Duration = 6.seconds
    }
}
