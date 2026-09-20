package com.example.shelfplayer.playback

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #36 — one continuity owner for the two measured Android Auto lifecycle seams.
 *
 * Arrival and departure deliberately remain distinct phases:
 *
 * - an arrival focus loss is captured before the first 0→1 car-controller bind;
 * - a departure focus loss is paired with the last 1→0 controller disconnect, in either callback order.
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
internal class CarLifecycleContinuityGate(
    private val pairingWindow: Duration = DEFAULT_PAIRING_WINDOW,
) {
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
        BoundaryWaitingForFocusLoss,
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

    internal data class Decision(
        val phase: Phase,
        val status: Status,
        val reason: Reason,
        val target: Target? = null,
    )

    internal data class ConsumeResult(
        val accepted: Boolean,
        val reason: Reason,
    )

    private data class Identity(
        val outputId: String,
        val generation: Long,
        val explicitSelectionSequence: Long,
    )

    private data class FocusCandidate(
        val at: Duration,
        val phase: Phase,
        val identity: Identity,
    )

    private data class DepartureMarker(
        val at: Duration,
        val identity: Identity,
    )

    private var focusCandidate: FocusCandidate? = null
    private var departureMarker: DepartureMarker? = null
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
            // The final 1→0 disconnect may have happened before Media3 reports focus loss. In that ordering
            // onCarDeparture has already copied the stable identity into departureMarker. Route/device
            // emissions after disconnect must retire the connected-session snapshot without erasing that
            // bounded lifecycle marker; a newer arrival, deliberate pause/book boundary, or the focus pairing
            // itself will consume/cancel it.
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
     * When the car is already connected the event belongs to the departure side. When the last disconnect
     * happened first, the stored departure marker lets this callback complete that pair immediately.
     */
    fun onAudioFocusLoss(
        at: Duration,
        heardRoute: RouteHeardOwnership.HeardRoute?,
        headsetId: String?,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
        carConnected: Boolean,
    ): Decision {
        if (!carConnected) {
            resolveDepartureAfterBoundary(
                focusAt = at,
                currentGeneration = currentGeneration,
                explicitSelectionSequence = explicitSelectionSequence,
            )?.let { return it }
        }

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
    fun onCarArrival(
        arrivedAt: Duration,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
    ): Decision {
        // A new 0→1 binding cancels any unconsumed departure marker from an earlier 1→0 transition.
        departureMarker = null

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
     * If Media3 already reported focus loss, the departure is ready immediately. If the controller boundary
     * arrives first while headset playback is still active, remember that boundary and let the later measured
     * focus loss complete the pair. A paused book never creates that marker.
     */
    fun onCarDeparture(
        departedAt: Duration,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
        playbackActive: Boolean,
    ): Decision {
        val candidate = focusCandidate
        if (candidate != null && candidate.phase == Phase.Departure) {
            val invalid = invalidReason(
                identity = candidate.identity,
                currentGeneration = currentGeneration,
                explicitSelectionSequence = explicitSelectionSequence,
                interval = departedAt - candidate.at,
            )
            if (invalid == null) {
                val target = candidate.identity.target(Phase.Departure)
                focusCandidate = null
                departureMarker = null
                activeRecovery = target
                return Decision(
                    phase = Phase.Departure,
                    status = Status.Ready,
                    reason = Reason.BoundaryMatchedFocusLoss,
                    target = target,
                )
            }
            focusCandidate = null
            activeRecovery = null
            if (!playbackActive) {
                return Decision(Phase.Departure, Status.Rejected, invalid)
            }
        } else if (candidate != null) {
            focusCandidate = null
            activeRecovery = null
        }

        if (!playbackActive) {
            departureMarker = null
            return Decision(Phase.Departure, Status.Rejected, Reason.NoPlayingCarHeadset)
        }

        val identity = currentConnectedPlayback(currentGeneration, explicitSelectionSequence)
            ?: return Decision(Phase.Departure, Status.Rejected, Reason.NoPlayingCarHeadset)

        departureMarker = DepartureMarker(at = departedAt, identity = identity)
        return Decision(
            phase = Phase.Departure,
            status = Status.Armed,
            reason = Reason.BoundaryWaitingForFocusLoss,
            target = identity.target(Phase.Departure),
        )
    }

    /** True only while the resolved target remains the single active lifecycle recovery. */
    fun isCurrent(
        target: Target,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
    ): Boolean = activeRecovery == target &&
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
        departureMarker = null
        if (reason != null) return ConsumeResult(accepted = false, reason = reason)
        return ConsumeResult(accepted = true, reason = Reason.Eligible)
    }

    /** A newer Play supersedes pending automatic recovery but does not rewrite route-heard ownership. */
    fun cancelPending() {
        focusCandidate = null
        departureMarker = null
        activeRecovery = null
    }

    /** A deliberate/non-focus pause or book/session boundary invalidates every continuity fact. */
    fun cancelAll() {
        cancelPending()
        connectedPlayback = null
    }

    private fun resolveDepartureAfterBoundary(
        focusAt: Duration,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
    ): Decision? {
        val marker = departureMarker ?: return null
        val invalid = invalidReason(
            identity = marker.identity,
            currentGeneration = currentGeneration,
            explicitSelectionSequence = explicitSelectionSequence,
            interval = focusAt - marker.at,
        )
        departureMarker = null
        if (invalid != null) {
            connectedPlayback = null
            return null
        }

        val target = marker.identity.target(Phase.Departure)
        focusCandidate = null
        activeRecovery = target
        return Decision(
            phase = Phase.Departure,
            status = Status.Ready,
            reason = Reason.BoundaryMatchedFocusLoss,
            target = target,
        )
    }

    private fun currentConnectedPlayback(
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
    ): Identity? = connectedPlayback?.takeIf { identity ->
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

    private fun eligibilityReason(
        target: Target,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
    ): Reason? = when {
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
         * Arrival was measured at two and four seconds. Departure uses the same controller/focus lifecycle
         * correlation bound until the next physical capture establishes a narrower ordering.
         */
        val DEFAULT_PAIRING_WINDOW: Duration = 6.seconds
    }
}
