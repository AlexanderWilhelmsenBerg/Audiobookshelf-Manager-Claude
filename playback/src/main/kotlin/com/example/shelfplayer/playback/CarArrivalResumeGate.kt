package com.example.shelfplayer.playback

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #36 — pairs the measured Android Auto audio-focus pause with one car arrival.
 *
 * Two physical drives on 2026-09-19 measured the same mechanism on the target setup:
 *
 * 1. BookWave was actively playing through the #11-owned headset.
 * 2. Media3 set playWhenReady=false with AUDIO_FOCUS_LOSS.
 * 3. the first Android Auto controller bound a few seconds later;
 * 4. Android could transiently remove/re-add that headset while the car was binding.
 *
 * The pause-time headset therefore becomes an immutable continuity target before live route evidence can
 * flap. The target is still generation- and explicit-intent-bound, and the candidate is consumed only after
 * the routing layer has secured that exact headset again.
 *
 * Not thread-safe by design: PlaybackService calls it from the player/main-thread boundary.
 */
internal class CarArrivalResumeGate(private val pairingWindow: Duration = DEFAULT_PAIRING_WINDOW) {
    internal data class Target(
        val outputId: String,
        val generation: Long,
        val explicitSelectionSequence: Long,
    )

    private data class Pending(
        val pausedAt: Duration,
        val generation: Long,
        val outputId: String,
        val explicitSelectionSequence: Long,
    )

    private var pending: Pending? = null

    /**
     * Records an AUDIO_FOCUS_LOSS only when #11 says the current book was actually heard in this headset.
     */
    fun onAudioFocusLoss(
        at: Duration,
        heardRoute: RouteHeardOwnership.HeardRoute?,
        headsetId: String?,
        explicitSelectionSequence: Long,
    ) {
        pending = heardRoute
            ?.takeIf { heard -> headsetId != null && heard.outputId == headsetId }
            ?.let { heard ->
                Pending(
                    pausedAt = at,
                    generation = heard.generation,
                    outputId = heard.outputId,
                    explicitSelectionSequence = explicitSelectionSequence,
                )
            }
    }

    /** Any newer listener/playback event makes the old focus-loss candidate unsafe to act on. */
    fun cancel() {
        pending = null
    }

    /**
     * Resolves the first car bind to the pause-time headset without consuming it yet.
     *
     * Routing may take a bounded moment because Android Auto can temporarily remove/re-add A2DP while it
     * binds. Holding the candidate until that exact target is secured lets the service survive that flap
     * without reconstructing ownership from live [RouteHeardOwnership.heardRoute].
     */
    fun targetForCarArrival(
        arrivedAt: Duration,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
    ): Target? {
        val candidate = pending ?: return null
        val ageAtArrival = arrivedAt - candidate.pausedAt
        val valid =
            ageAtArrival >= Duration.ZERO &&
                ageAtArrival <= pairingWindow &&
                currentGeneration == candidate.generation &&
                explicitSelectionSequence == candidate.explicitSelectionSequence
        if (!valid) {
            pending = null
            return null
        }
        return Target(
            outputId = candidate.outputId,
            generation = candidate.generation,
            explicitSelectionSequence = candidate.explicitSelectionSequence,
        )
    }

    /** True only while the resolved target still belongs to the same pending listener/book context. */
    fun isCurrent(
        target: Target,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
    ): Boolean {
        val candidate = pending ?: return false
        return candidate.outputId == target.outputId &&
            candidate.generation == target.generation &&
            candidate.explicitSelectionSequence == target.explicitSelectionSequence &&
            currentGeneration == target.generation &&
            explicitSelectionSequence == target.explicitSelectionSequence
    }

    /**
     * Consumes the candidate only after routing has secured the exact pause-time headset.
     *
     * A refusal also consumes it: one measured focus loss gets at most one first-car-arrival recovery.
     */
    fun resumeForCarArrival(
        target: Target,
        currentGeneration: Long?,
        headsetId: String?,
        explicitSelectionSequence: Long,
        resume: (String) -> Unit,
    ): Boolean {
        val valid = isCurrent(
            target = target,
            currentGeneration = currentGeneration,
            explicitSelectionSequence = explicitSelectionSequence,
        ) && headsetId == target.outputId
        pending = null
        if (!valid) return false
        resume(target.outputId)
        return true
    }

    private companion object {
        /** The measured pause→first-bind intervals were two and four seconds; six leaves bounded startup jitter. */
        val DEFAULT_PAIRING_WINDOW: Duration = 6.seconds
    }
}
