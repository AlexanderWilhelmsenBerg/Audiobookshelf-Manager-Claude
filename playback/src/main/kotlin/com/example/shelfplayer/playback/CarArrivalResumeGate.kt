package com.example.shelfplayer.playback

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #36 — one continuity owner for the two measured Android Auto lifecycle seams.
 *
 * Arrival and departure deliberately remain distinct phases:
 *
 * - an arrival focus loss is captured before the first 0→1 car-controller bind;
 * - a departure focus loss is paired only when it was observed while the car was still connected and the
 *   last 1→0 controller disconnect follows it.
 *
 * The exact headset is captured only from route-heard evidence while playback was real. During a connected
 * car session [observePlayingHeadset] keeps the last positively proven headset as stable continuity evidence,
 * so a transient Android device-list omission cannot erase listener intent. That stable record is never
 * allowed to beat a newer book generation or explicit output-selection sequence.
 *
 * This class decides whether recovery is safe. It never chooses another route and never calls Play.
 *
 * Not thread-safe by design: [PlaybackService] calls it from the player/session main-thread boundary.
 */
internal class CarArrivalResumeGate(private val pairingWindow: Duration = DEFAULT_PAIRING_WINDOW) {
    internal enum class Phase {
        Arrival,
        Departure,
    }

    internal enum class Status {
        Armed,
        Ready,
        Rejected,
    }

    internal enum class Reason {
        FocusLossWaitingForBoundary,
        BoundaryMatchedFocusLoss,
        NoQualifyingHeadset,
        NoPendingFocusLoss,
        NoPlayingCarHeadset,
        OutsidePairingWindow,
        GenerationChanged,
        ExplicitSelectionChanged,
        WrongLifecyclePhase,
        CandidateInvalidated,
        WrongHeadset,
        Eligible,
    }

    internal data class Target(
        val outputId: String,
        val generation: Long,
        val explicitSelectionSequence: Long,
        val phase: Phase,
    )

    internal data class Decision(val phase: Phase, val status: Status, val reason: Reason, val target: Target? = null)

    internal data class ConsumeResult(val accepted: Boolean, val reason: Reason)

    private data class Identity(val outputId: String, val generation: Long, val explicitSelectionSequence: Long)

    private data class FocusCandidate(val at: Duration, val phase: Phase, val identity: Identity)

    private var focusCandidate: FocusCandidate? = null
    private var activeRecovery: Target? = null

    /**
     * Last headset positively heard while a car controller was present and playback was actually active.
     *
     * A missing live route does not clear this record. Only newer listener/book/playback intent can make it
     * ineligible, which is exactly what the generation and explicit-selection sequence guard below prove.
     */
    private var connectedPlayback: Identity? = null

    /**
     * Refreshes the stable car-session headset only from positive playback evidence.
     *
     * Passing no qualifying headset while the car is connected deliberately leaves the previous positive
     * observation intact: Android Auto has already been measured temporarily omitting that device.
     */
    fun observePlayingHeadset(
        heardRoute: RouteHeardOwnership.HeardRoute?,
        headsetId: String?,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
        carConnected: Boolean,
    ) {
        if (!carConnected) {
            connectedPlayback = null
            return
        }
        identityOf(
            heardRoute = heardRoute,
            headsetId = headsetId,
            currentGeneration = currentGeneration,
            explicitSelectionSequence = explicitSelectionSequence,
        )?.let { connectedPlayback = it }
    }

    /**
     * Records the measured Media3 AUDIO_FOCUS_LOSS.
     *
     * When the car is already connected the event belongs to the departure side. A focus loss first observed
     * after the final disconnect is intentionally not treated as departure evidence: that callback order has
     * not been measured physically and could be an unrelated call/navigation/media focus event.
     */
    fun onAudioFocusLoss(
        at: Duration,
        heardRoute: RouteHeardOwnership.HeardRoute?,
        headsetId: String?,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
        carConnected: Boolean,
    ): Decision {
        val phase = if (carConnected) Phase.Departure else Phase.Arrival
        val liveIdentity = identityOf(
            heardRoute = heardRoute,
            headsetId = headsetId,
            currentGeneration = currentGeneration,
            explicitSelectionSequence = explicitSelectionSequence,
        )
        val identity = if (phase == Phase.Departure) {
            currentConnectedPlayback(currentGeneration, explicitSelectionSequence) ?: liveIdentity
        } else {
            liveIdentity
        }

        if (identity == null) {
            focusCandidate = null
            activeRecovery = null
            return Decision(
                phase = phase,
                status = Status.Rejected,
                reason = Reason.NoQualifyingHeadset,
            )
        }

        focusCandidate = FocusCandidate(at = at, phase = phase, identity = identity)
        activeRecovery = null
        return Decision(
            phase = phase,
            status = Status.Armed,
            reason = Reason.FocusLossWaitingForBoundary,
            target = identity.target(phase),
        )
    }

    /** Pairs only an arrival-phase focus loss with the first 0→1 car-controller binding. */
    fun onCarArrival(arrivedAt: Duration, currentGeneration: Long?, explicitSelectionSequence: Long): Decision {
        val candidate = focusCandidate
            ?: return Decision(Phase.Arrival, Status.Rejected, Reason.NoPendingFocusLoss)
        if (candidate.phase != Phase.Arrival) {
            focusCandidate = null
            activeRecovery = null
            return Decision(Phase.Arrival, Status.Rejected, Reason.WrongLifecyclePhase)
        }

        val invalid = invalidReason(
            identity = candidate.identity,
            currentGeneration = currentGeneration,
            explicitSelectionSequence = explicitSelectionSequence,
            interval = arrivedAt - candidate.at,
        )
        if (invalid != null) {
            focusCandidate = null
            activeRecovery = null
            return Decision(Phase.Arrival, Status.Rejected, invalid)
        }

        val target = candidate.identity.target(Phase.Arrival)
        focusCandidate = null
        activeRecovery = target
        return Decision(
            phase = Phase.Arrival,
            status = Status.Ready,
            reason = Reason.BoundaryMatchedFocusLoss,
            target = target,
        )
    }

    /**
     * Handles only the last 1→0 car-controller disconnect.
     *
     * Recovery is allowed only when Media3 already reported a departure-phase audio-focus loss while the car
     * was still connected. The opposite callback order is diagnostic-only until a physical capture proves it
     * is part of the car transition; guessing across that boundary could turn an unrelated later focus loss
     * into automatic playback.
     */
    fun onCarDeparture(departedAt: Duration, currentGeneration: Long?, explicitSelectionSequence: Long): Decision {
        val candidate = focusCandidate
            ?: return Decision(Phase.Departure, Status.Rejected, Reason.NoPendingFocusLoss)
        if (candidate.phase != Phase.Departure) {
            focusCandidate = null
            activeRecovery = null
            return Decision(Phase.Departure, Status.Rejected, Reason.WrongLifecyclePhase)
        }

        val invalid = invalidReason(
            identity = candidate.identity,
            currentGeneration = currentGeneration,
            explicitSelectionSequence = explicitSelectionSequence,
            interval = departedAt - candidate.at,
        )
        focusCandidate = null
        if (invalid != null) {
            activeRecovery = null
            return Decision(Phase.Departure, Status.Rejected, invalid)
        }

        val target = candidate.identity.target(Phase.Departure)
        activeRecovery = target
        return Decision(
            phase = Phase.Departure,
            status = Status.Ready,
            reason = Reason.BoundaryMatchedFocusLoss,
            target = target,
        )
    }

    /** True only while the resolved target remains the single active lifecycle recovery. */
    fun isCurrent(target: Target, currentGeneration: Long?, explicitSelectionSequence: Long): Boolean =
        activeRecovery == target &&
            eligibilityReason(target, currentGeneration, explicitSelectionSequence) == null

    /**
     * Final one-shot check after routing has secured the exact captured headset.
     *
     * A refusal also consumes the active recovery. One lifecycle transition gets at most one Play attempt.
     */
    fun consumeRecovery(
        target: Target,
        currentGeneration: Long?,
        headsetId: String?,
        explicitSelectionSequence: Long,
    ): ConsumeResult {
        val reason = when {
            activeRecovery != target -> Reason.CandidateInvalidated

            else -> eligibilityReason(target, currentGeneration, explicitSelectionSequence)
                ?: if (headsetId != target.outputId) Reason.WrongHeadset else null
        }
        activeRecovery = null
        if (reason != null) return ConsumeResult(accepted = false, reason = reason)
        return ConsumeResult(accepted = true, reason = Reason.Eligible)
    }

    /** A newer Play supersedes pending automatic recovery but does not rewrite route-heard ownership. */
    fun cancelPending() {
        focusCandidate = null
        activeRecovery = null
    }

    /** A deliberate/non-focus pause or book/session boundary invalidates every continuity fact. */
    fun cancelAll() {
        cancelPending()
        connectedPlayback = null
    }

    private fun currentConnectedPlayback(currentGeneration: Long?, explicitSelectionSequence: Long): Identity? =
        connectedPlayback?.takeIf { identity ->
            identity.generation == currentGeneration &&
                identity.explicitSelectionSequence == explicitSelectionSequence
        }

    private fun identityOf(
        heardRoute: RouteHeardOwnership.HeardRoute?,
        headsetId: String?,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
    ): Identity? = heardRoute
        ?.takeIf { heard ->
            headsetId != null &&
                heard.outputId == headsetId &&
                heard.generation == currentGeneration
        }
        ?.let { heard ->
            Identity(
                outputId = heard.outputId,
                generation = heard.generation,
                explicitSelectionSequence = explicitSelectionSequence,
            )
        }

    private fun invalidReason(
        identity: Identity,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
        interval: Duration,
    ): Reason? = when {
        identity.generation != currentGeneration -> Reason.GenerationChanged
        identity.explicitSelectionSequence != explicitSelectionSequence -> Reason.ExplicitSelectionChanged
        interval < Duration.ZERO || interval > pairingWindow -> Reason.OutsidePairingWindow
        else -> null
    }

    private fun eligibilityReason(target: Target, currentGeneration: Long?, explicitSelectionSequence: Long): Reason? =
        when {
            target.generation != currentGeneration -> Reason.GenerationChanged
            target.explicitSelectionSequence != explicitSelectionSequence -> Reason.ExplicitSelectionChanged
            else -> null
        }

    private fun Identity.target(phase: Phase) = Target(
        outputId = outputId,
        generation = generation,
        explicitSelectionSequence = explicitSelectionSequence,
        phase = phase,
    )

    private companion object {
        /**
         * Arrival was measured at two and four seconds. Departure is permitted only when focus loss is already
         * observed while the car remains connected; this same conservative bound limits how long that evidence
         * may wait for the final 1→0 disconnect. The opposite ordering remains diagnostic-only.
         */
        val DEFAULT_PAIRING_WINDOW: Duration = 6.seconds
    }
}
