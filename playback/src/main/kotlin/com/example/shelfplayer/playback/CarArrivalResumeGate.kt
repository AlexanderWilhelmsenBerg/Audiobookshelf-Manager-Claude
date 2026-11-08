package com.example.shelfplayer.playback

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One continuity owner for measured Auto lifecycle seams and brief app-issued safety interruptions.
 *
 * Arrival and departure deliberately remain distinct phases:
 *
 * - an arrival focus loss is paired with a car-specific arrival boundary;
 * - a departure focus loss is paired with a car-specific departure boundary.
 * - an app safety pause may recover its exact headset within two seconds without a car boundary.
 *
 * The exact headset is captured only from route-heard evidence while playback was real. [observePlayingHeadset]
 * keeps the last positively proven headset as stable transition evidence before and during a car session, so
 * Android's measured transient device-list omission cannot erase listener intent before focus loss is reported.
 * Positive evidence of a different route clears that snapshot. The record is never allowed to beat a newer
 * book generation or explicit output-selection sequence.
 *
 * This class decides whether recovery is safe. It never chooses another route and never calls Play.
 *
 * Not thread-safe by design: [PlaybackService] calls it from the player/session main-thread boundary.
 */
internal class CarArrivalResumeGate private constructor(
    private val pairingWindow: Duration,
    private val now: () -> Duration,
    private val trustedIdentity: (String) -> Boolean,
) {
    constructor(pairingWindow: Duration = DEFAULT_PAIRING_WINDOW) : this(
        pairingWindow,
        { Duration.ZERO },
        { false },
    )

    constructor(
        now: () -> Duration,
        trustedIdentity: (String) -> Boolean,
        pairingWindow: Duration = DEFAULT_PAIRING_WINDOW,
    ) : this(pairingWindow, now, trustedIdentity)

    internal enum class Phase {
        Arrival,
        Departure,
        HeadsetReturn,
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
        NoFreshPlaybackAfterIntentReset,
        NoPendingFocusLoss,
        NoPlayingCarHeadset,
        OutsidePairingWindow,
        GenerationChanged,
        ExplicitSelectionChanged,
        WrongLifecyclePhase,
        CandidateInvalidated,
        WrongHeadset,
        IdentityUntrusted,
        RecoveryExpired,
        ConfirmedEpisodeInterrupted,
        SafetyPausedHeadsetReturn,
        Eligible,
    }

    /**
     * The gate-issued instance is also the one-shot episode identity. Equal route fields from
     * another lifecycle interval do not authorize an older suspended effect.
     */
    internal data class Target(
        val outputId: String,
        val generation: Long,
        val explicitSelectionSequence: Long,
        val phase: Phase,
        val deadline: Duration? = null,
        val episodeId: Long = 0,
        val attemptOrdinal: Int = 1,
    )

    internal data class Decision(val phase: Phase, val status: Status, val reason: Reason, val target: Target? = null)

    internal data class ConsumeResult(val accepted: Boolean, val reason: Reason)

    private data class FocusCandidate(
        val at: Duration,
        val phase: Phase,
        val identity: CarContinuityIdentity,
        val deadline: Duration?,
    )
    private data class DepartureBoundary(val at: Duration, val identity: CarContinuityIdentity)
    private data class ArrivalBoundary(val at: Duration, val identity: CarContinuityIdentity, val deadline: Duration?)

    private val evidence = CarContinuityEvidence(pairingWindow, trustedIdentity)
    private val headsetReturn = HeadsetReturnRecovery(now, trustedIdentity)

    private var focusCandidate: FocusCandidate? = null
    private var projectionDeparture: DepartureBoundary? = null
    private var firstArrival: ArrivalBoundary? = null
    private var arrivalRecorded = false
    private var activeRecovery: Target? = null
    private var requiresFreshPlaybackEvidence = false
    private var safetyPausedAt: Duration? = null
    private var qualifiedEpisode: CarArrivalEpisode? = null
    private var nextQualifiedEpisodeId = 0L

    /** Retain this interruption's evidence, without extending it on repeated safety callbacks. */
    fun onRouteSafetyPause(
        at: Duration,
        headsetId: String? = null,
        currentGeneration: Long? = null,
        explicitSelectionSequence: Long = 0,
        carConnected: Boolean = false,
    ): Decision? {
        val previous = safetyPausedAt
        if (previous == null || at < previous) safetyPausedAt = at
        val next = qualifiedEpisode?.rearm(
            at,
            headsetId,
            currentGeneration,
            explicitSelectionSequence,
            carConnected,
            trustedIdentity,
        )
        if (next != null) {
            activeRecovery = next
            return Decision(Phase.Arrival, Status.Ready, Reason.ConfirmedEpisodeInterrupted, next)
        }
        playingHeadset?.let { beginQualifiedEpisode(it, at) }
        if (previous != null || qualifiedEpisode?.issuedTarget != null) return null
        val identity = currentPlayingHeadset(currentGeneration, explicitSelectionSequence)
            ?.takeIf { it.matches(headsetId, currentGeneration, explicitSelectionSequence) } ?: return null
        return headsetReturn.issue(identity, at)?.also { activeRecovery = it }?.let {
            Decision(Phase.HeadsetReturn, Status.Ready, Reason.SafetyPausedHeadsetReturn, it)
        }
    }

    /** Inventory cleanup is not listener intent. Retain only this already qualified, bounded episode. */
    fun retainsInterruptedHeadset(id: String, generation: Long?, selectionSequence: Long): Boolean {
        val episode = qualifiedEpisode ?: return false
        val identity = episode.identity
        val at = now()
        return !episode.closed && !requiresFreshPlaybackEvidence && identity.outputId == id &&
            identity.generation == generation && identity.explicitSelectionSequence == selectionSequence &&
            at >= episode.startedAt && at < episode.deadline && trustedIdentity(id)
    }

    private fun expireSafetyPause(at: Duration): Boolean {
        val qualified = qualifiedEpisode?.takeUnless { it.closed }
        val expired = if (qualified != null) {
            val age = at - qualified.startedAt
            age < Duration.ZERO || age >= QUALIFIED_ARRIVAL_WINDOW ||
                !trustedIdentity(qualified.identity.outputId)
        } else {
            safetyPausedAt?.let { at - it !in Duration.ZERO..pairingWindow } == true
        }
        if (expired) cancelAll()
        return expired
    }

    /**
     * Last headset positively heard while playback was actually active.
     *
     * A missing live route does not clear this record: Android Auto has physically been measured removing the
     * A2DP endpoint before Media3 reports focus loss. A positive non-headset route does clear it, so a genuine
     * route move cannot borrow stale headset authority.
     */
    private var playingHeadset: CarContinuityIdentity? = null

    /** Refreshes stable transition evidence only from actual playing-route observations. */
    fun observePlayingHeadset(
        heardRoute: RouteHeardOwnership.HeardRoute?,
        headsetId: String?,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
    ): Target? {
        val evidence = heardRoute?.evidence
        if (evidence == RouteHeardEvidence.FrameworkPolicyWhilePlaying ||
            evidence == RouteHeardEvidence.SelectedPreferenceWhilePlaying
        ) {
            return null
        }
        val identity = identityOf(
            heardRoute = heardRoute,
            headsetId = headsetId,
            currentGeneration = currentGeneration,
            explicitSelectionSequence = explicitSelectionSequence,
            captureTrust = true,
        )
        // Inventory churn can replace the live heard row with a speaker policy prediction while the
        // AudioTrack is unresolved. That is neither audible progress nor proof of a route move.
        if (identity == null && heardRoute?.evidence != RouteHeardEvidence.ActualAudioTrackWhilePlaying) return null
        requiresFreshPlaybackEvidence = false
        safetyPausedAt = null
        playingHeadset = identity
        return confirmObservedHeadset(identity, evidence == RouteHeardEvidence.ActualAudioTrackWhilePlaying)
    }

    private fun confirmObservedHeadset(identity: CarContinuityIdentity?, actualTrack: Boolean): Target? {
        val episode = qualifiedEpisode?.takeIf { !it.closed && it.identity == identity && actualTrack }
        qualifiedEpisode = episode
        if (episode == null) return null
        return episode.issuedTarget?.takeIf { episode.attempts.observeActualTrack(it) }
    }

    /**
     * Records the measured Media3 AUDIO_FOCUS_LOSS.
     *
     * A first-arrival boundary captured synchronously before route settlement may pair a later focus loss.
     * Outside that one-shot window an established session uses the existing departure semantics. A focus
     * loss first observed after final disconnect remains ineligible without the physical projection edge.
     */
    fun onAudioFocusLoss(
        at: Duration,
        heardRoute: RouteHeardOwnership.HeardRoute?,
        headsetId: String?,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
        carConnected: Boolean,
    ): Decision {
        // An app safety interruption cannot override a later platform interruption.
        headsetReturn.clear()
        if (activeRecovery?.phase == Phase.HeadsetReturn) activeRecovery = null
        // A fresh departure focus callback starts its own conservative pairing clock. An earlier generic
        // safety pause cannot make this new departure callback stale before its projection boundary arrives.
        if (carConnected) finishConfirmedArrival(currentGeneration, explicitSelectionSequence, at)
        if (carConnected && qualifiedEpisode == null && safetyPausedAt?.let { at - it > pairingWindow } == true) {
            safetyPausedAt = null
        }
        val expired = expireSafetyPause(at)
        if (requiresFreshPlaybackEvidence) {
            return Decision(
                phase = if (carConnected) Phase.Departure else Phase.Arrival,
                status = Status.Rejected,
                reason = if (expired) Reason.OutsidePairingWindow else Reason.NoFreshPlaybackAfterIntentReset,
            )
        }
        return onEligibleAudioFocusLoss(
            at = at,
            heardRoute = heardRoute,
            headsetId = headsetId,
            currentGeneration = currentGeneration,
            explicitSelectionSequence = explicitSelectionSequence,
            carConnected = carConnected,
        )
    }

    /**
     * Media3 may pause for AUDIO_BECOMING_NOISY before its focus callback. Once playWhenReady is false,
     * there may be no second listener transition when focus later changes. Retain exactly the same
     * car-boundary, previously-audible-headset and explicit-intent protections as a focus loss.
     *
     * This records a candidate only: it never authorizes Play without a timely car-specific boundary.
     * An ordinary unplug therefore remains paused and cannot fall through to the phone speaker.
     */
    fun onAudioBecomingNoisy(
        at: Duration,
        heardRoute: RouteHeardOwnership.HeardRoute?,
        headsetId: String?,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
        carConnected: Boolean,
    ): Decision = pendingHeadsetReturn(currentGeneration, explicitSelectionSequence)?.let {
        // Same disconnect, same unconsumed claim. Noisy is not permission for another Play or deadline.
        Decision(it.phase, Status.Ready, Reason.SafetyPausedHeadsetReturn, it)
    } ?: onAudioFocusLoss(
        at = at,
        heardRoute = heardRoute,
        headsetId = headsetId,
        currentGeneration = currentGeneration,
        explicitSelectionSequence = explicitSelectionSequence,
        carConnected = carConnected,
    )

    /** The existing bounded arrival/departure correlation, only after a fresh audible-play observation. */
    private fun onEligibleAudioFocusLoss(
        at: Duration,
        heardRoute: RouteHeardOwnership.HeardRoute?,
        headsetId: String?,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
        carConnected: Boolean,
    ): Decision {
        val matched = qualifiedEpisode?.unconfirmedTarget?.let { target ->
            existingQualifiedClaim(target, currentGeneration, explicitSelectionSequence)
        } ?: matchProjectionDeparture(at, currentGeneration, explicitSelectionSequence, carConnected)
            ?: matchFocusToFirstArrival(
                at = at,
                heardRoute = heardRoute,
                headsetId = headsetId,
                currentGeneration = currentGeneration,
                explicitSelectionSequence = explicitSelectionSequence,
            )
        if (matched != null) return matched

        val phase = if (carConnected) Phase.Departure else Phase.Arrival
        val liveIdentity = identityOf(
            heardRoute = heardRoute,
            headsetId = headsetId,
            currentGeneration = currentGeneration,
            explicitSelectionSequence = explicitSelectionSequence,
        )
        val identity = liveIdentity ?: currentPlayingHeadset(currentGeneration, explicitSelectionSequence)

        if (identity == null) {
            focusCandidate = null
            activeRecovery = null
            return Decision(
                phase = phase,
                status = Status.Rejected,
                reason = Reason.NoQualifyingHeadset,
            )
        }

        val episode = if (phase == Phase.Arrival) beginQualifiedEpisode(identity, at) else null
        val deadline = episode?.deadline
        focusCandidate = FocusCandidate(
            at = if (phase == Phase.Arrival) episode?.startedAt ?: safetyPausedAt ?: at else at,
            phase = phase,
            identity = identity,
            deadline = deadline,
        )
        activeRecovery = null
        return Decision(
            phase = phase,
            status = Status.Armed,
            reason = Reason.FocusLossWaitingForBoundary,
            target = identity.target(phase, deadline),
        )
    }

    private fun matchProjectionDeparture(
        at: Duration,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
        carConnected: Boolean,
    ): Decision? {
        val boundary = projectionDeparture ?: return null
        projectionDeparture = null
        val invalid = evidence.invalidReason(
            identity = boundary.identity,
            currentGeneration = currentGeneration,
            explicitSelectionSequence = explicitSelectionSequence,
            interval = at - boundary.at,
        )
        if (carConnected || invalid != null) return null
        val target = boundary.identity.target(Phase.Departure)
        focusCandidate = null
        activeRecovery = target
        return Decision(
            phase = Phase.Departure,
            status = Status.Ready,
            reason = Reason.BoundaryMatchedFocusLoss,
            target = target,
        )
    }

    /** Pairs arrival focus with only the first controller/projection boundary of this car session. */
    fun onCarArrival(arrivedAt: Duration, currentGeneration: Long?, explicitSelectionSequence: Long): Decision {
        expireSafetyPause(arrivedAt)
        pendingHeadsetReturn(currentGeneration, explicitSelectionSequence)
            ?.let { return Decision(it.phase, Status.Ready, Reason.SafetyPausedHeadsetReturn, it) }
        projectionDeparture = null
        if (arrivalRecorded || qualifiedEpisode?.issuedTarget != null) {
            val issued = qualifiedEpisode?.issuedTarget
            return issued?.let { existingQualifiedClaim(it, currentGeneration, explicitSelectionSequence) }
                ?: Decision(Phase.Arrival, Status.Rejected, Reason.NoPendingFocusLoss)
        }
        arrivalRecorded = true
        val candidate = focusCandidate
        return if (candidate == null) {
            armFirstArrival(arrivedAt, currentGeneration, explicitSelectionSequence)
        } else {
            matchArrivalFocus(candidate, arrivedAt, currentGeneration, explicitSelectionSequence)
        }
    }

    private fun armFirstArrival(
        arrivedAt: Duration,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
    ): Decision {
        val heard = currentPlayingHeadset(currentGeneration, explicitSelectionSequence)
            ?: return Decision(Phase.Arrival, Status.Rejected, Reason.NoPendingFocusLoss)
        val episode = beginQualifiedEpisode(heard, arrivedAt)
        firstArrival = ArrivalBoundary(at = arrivedAt, identity = heard, deadline = episode?.deadline)
        activeRecovery = null
        return Decision(
            Phase.Arrival,
            Status.Armed,
            Reason.BoundaryWaitingForFocusLoss,
            heard.target(Phase.Arrival, episode?.deadline),
        )
    }

    private fun matchArrivalFocus(
        candidate: FocusCandidate,
        arrivedAt: Duration,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
    ): Decision {
        if (candidate.phase != Phase.Arrival) {
            focusCandidate = null
            activeRecovery = null
            return Decision(Phase.Arrival, Status.Rejected, Reason.WrongLifecyclePhase)
        }

        val invalid = evidence.invalidReason(
            identity = candidate.identity,
            currentGeneration = currentGeneration,
            explicitSelectionSequence = explicitSelectionSequence,
            interval = arrivedAt - candidate.at,
            eventAt = arrivedAt,
            deadline = candidate.deadline,
        )
        if (invalid != null) {
            focusCandidate = null
            activeRecovery = null
            return Decision(Phase.Arrival, Status.Rejected, invalid)
        }

        val target = issueArrivalRecovery(candidate.identity, candidate.deadline)
        focusCandidate = null
        return Decision(
            phase = Phase.Arrival,
            status = Status.Ready,
            reason = Reason.BoundaryMatchedFocusLoss,
            target = target,
        )
    }

    /**
     * Pairs a delayed Media3 focus callback with the first boundary already captured on the callback thread.
     * A positive observation of another headset cannot use the old route even if the selection sequence matches.
     */
    private fun matchFocusToFirstArrival(
        at: Duration,
        heardRoute: RouteHeardOwnership.HeardRoute?,
        headsetId: String?,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
    ): Decision? {
        val boundary = firstArrival ?: return null
        firstArrival = null
        val invalid = evidence.invalidReason(
            identity = boundary.identity,
            currentGeneration = currentGeneration,
            explicitSelectionSequence = explicitSelectionSequence,
            interval = at - boundary.at,
            eventAt = at,
            deadline = boundary.deadline,
        )
        if (invalid != null) return null
        val live = identityOf(heardRoute, headsetId, currentGeneration, explicitSelectionSequence)
        if (live != null && live.outputId != boundary.identity.outputId) return null

        focusCandidate = null
        val target = issueArrivalRecovery(boundary.identity, boundary.deadline)
        activeRecovery = target
        return Decision(Phase.Arrival, Status.Ready, Reason.BoundaryMatchedFocusLoss, target)
    }

    /**
     * Records the strong physical projection-exit boundary.
     *
     * Unlike a legacy MediaSession controller disconnect, this edge is car-specific enough to be retained
     * briefly when it arrives before focus loss. That supports both physical event orders without turning an
     * unrelated later focus loss into generic auto-resume.
     */
    fun onProjectionDeparture(
        departedAt: Duration,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
    ): Decision {
        finishConfirmedArrival(currentGeneration, explicitSelectionSequence)
        expireSafetyPause(departedAt)
        qualifiedEpisode?.unconfirmedTarget?.let {
            closeQualifiedArrivalEpisode()
            return Decision(Phase.Departure, Status.Rejected, Reason.CandidateInvalidated)
        }
        firstArrival = null
        arrivalRecorded = false
        val candidate = focusCandidate
        if (candidate != null) {
            val decision = onCarDeparture(departedAt, currentGeneration, explicitSelectionSequence)
            closeQualifiedArrivalEpisode()
            return decision
        }

        val identity = currentPlayingHeadset(currentGeneration, explicitSelectionSequence)
            ?: run {
                closeQualifiedArrivalEpisode()
                return Decision(Phase.Departure, Status.Rejected, Reason.NoPlayingCarHeadset)
            }
        projectionDeparture = DepartureBoundary(at = departedAt, identity = identity)
        activeRecovery = null
        closeQualifiedArrivalEpisode()
        return Decision(
            phase = Phase.Departure,
            status = Status.Armed,
            reason = Reason.BoundaryWaitingForFocusLoss,
            target = identity.target(Phase.Departure),
        )
    }

    /**
     * Handles the conservative legacy-controller fallback.
     *
     * Recovery is allowed only when Media3 already reported a departure-phase audio-focus loss while the car
     * session was still established. The opposite callback order remains forbidden for this weaker boundary;
     * only [onProjectionDeparture] may retain a boundary before focus loss.
     */
    fun onCarDeparture(departedAt: Duration, currentGeneration: Long?, explicitSelectionSequence: Long): Decision {
        qualifiedEpisode?.unconfirmedTarget?.let {
            closeQualifiedArrivalEpisode()
            return Decision(Phase.Departure, Status.Rejected, Reason.CandidateInvalidated)
        }
        firstArrival = null
        arrivalRecorded = false
        val candidate = focusCandidate
        val decision = when {
            candidate == null -> Decision(Phase.Departure, Status.Rejected, Reason.NoPendingFocusLoss)

            candidate.phase != Phase.Departure -> {
                activeRecovery = null
                Decision(Phase.Departure, Status.Rejected, Reason.WrongLifecyclePhase)
            }

            else -> resolveCarDeparture(candidate, departedAt, currentGeneration, explicitSelectionSequence)
        }
        focusCandidate = null
        closeQualifiedArrivalEpisode()
        return decision
    }

    private fun resolveCarDeparture(
        candidate: FocusCandidate,
        departedAt: Duration,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
    ): Decision {
        val invalid = evidence.invalidReason(
            identity = candidate.identity,
            currentGeneration = currentGeneration,
            explicitSelectionSequence = explicitSelectionSequence,
            interval = departedAt - candidate.at,
        )
        if (invalid != null) {
            activeRecovery = null
            return Decision(Phase.Departure, Status.Rejected, invalid)
        }
        val target = candidate.identity.target(Phase.Departure)
        activeRecovery = target
        return Decision(Phase.Departure, Status.Ready, Reason.BoundaryMatchedFocusLoss, target)
    }

    /** True only while the resolved target remains the single active lifecycle recovery. */
    fun pendingHeadsetReturn(currentGeneration: Long?, explicitSelectionSequence: Long): Target? =
        activeRecovery?.takeIf {
            it.phase == Phase.HeadsetReturn && isCurrent(it, currentGeneration, explicitSelectionSequence)
        }

    fun isCurrent(target: Target, currentGeneration: Long?, explicitSelectionSequence: Long): Boolean =
        activeRecovery === target &&
            (target.phase != Phase.HeadsetReturn || headsetReturn.valid(target)) &&
            evidence.targetReason(target, currentGeneration, explicitSelectionSequence, now()) == null

    /** Remaining time for an eligible qualified arrival route wait; null retains the ordinary route bound. */
    fun remainingWindow(target: Target): Duration? = target.deadline?.minus(now())?.coerceAtLeast(Duration.ZERO)

    /** The deadline/identity half of eligibility for async route probes after the one-shot claim is consumed. */
    fun episodeValid(target: Target): Boolean = if (target.phase == Phase.HeadsetReturn) {
        headsetReturn.valid(target)
    } else {
        target.deadline == null || qualifiedEpisode?.let { episode ->
            !episode.closed && episode.issuedTarget === target && episode.deadline == target.deadline &&
                now() < target.deadline && trustedIdentity(target.outputId)
        } == true
    }

    /**
     * Final one-shot check after routing has secured the exact captured headset.
     *
     * A refusal consumes only this episode. An obsolete callback cannot consume its successor.
     */
    fun consumeRecovery(
        target: Target,
        currentGeneration: Long?,
        headsetId: String?,
        explicitSelectionSequence: Long,
    ): ConsumeResult {
        if (activeRecovery !== target) return ConsumeResult(false, Reason.CandidateInvalidated)
        val reason = evidence.targetReason(target, currentGeneration, explicitSelectionSequence, now())
            ?: if (headsetId != target.outputId) Reason.WrongHeadset else null
        activeRecovery = null
        if (reason != null) return ConsumeResult(accepted = false, reason = reason)
        if (target.phase == Phase.HeadsetReturn && !headsetReturn.consume(target)) {
            return ConsumeResult(false, Reason.CandidateInvalidated)
        }
        if (target.phase == Phase.Arrival) {
            val episode = qualifiedEpisode
            if (episode != null && episode.issuedTarget === target) {
                episode.attempts.recordClaim(target)
            }
        }
        return ConsumeResult(accepted = true, reason = Reason.Eligible)
    }

    /** Confirmation needs both the exact actual AudioTrack route and the service's active-playback callback. */
    fun markRecoveryPlayIssued(target: Target) {
        qualifiedEpisode?.takeIf { it.issuedTarget === target }?.attempts?.markPlayIssued(target)
    }

    fun confirmRecoveryPlayback(target: Target): Boolean {
        val episode = qualifiedEpisode ?: return false
        if (episode.closed || episode.issuedTarget !== target || !episode.attempts.hasClaim) return false
        return episode.attempts.playbackBecameActive(target)
    }

    /** Cancel only work holding this gate-issued claim, leaving any newer episode intact. */
    fun cancelRecovery(target: Target): Boolean {
        if (activeRecovery !== target) return false
        cancelPending()
        return true
    }

    /** A newer Play supersedes pending automatic recovery but does not rewrite route-heard ownership. */
    fun cancelPending() {
        headsetReturn.cancelPending()
        focusCandidate = null
        firstArrival = null
        projectionDeparture = null
        activeRecovery = null
    }

    /** A deliberate/non-focus pause or book/session boundary invalidates every continuity fact. */
    fun cancelAll() {
        cancelPending()
        headsetReturn.clear()
        safetyPausedAt = null
        qualifiedEpisode = null
        requiresFreshPlaybackEvidence = true
        playingHeadset = null
    }

    private fun currentPlayingHeadset(
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
    ): CarContinuityIdentity? = playingHeadset?.takeIf { identity ->
        identity.generation == currentGeneration &&
            identity.explicitSelectionSequence == explicitSelectionSequence
    }

    private fun identityOf(
        heardRoute: RouteHeardOwnership.HeardRoute?,
        headsetId: String?,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
        captureTrust: Boolean = false,
    ): CarContinuityIdentity? = evidence.identityOf(
        heardRoute,
        headsetId,
        currentGeneration,
        explicitSelectionSequence,
        playingHeadset,
        captureTrust,
    )

    private fun beginQualifiedEpisode(identity: CarContinuityIdentity, at: Duration): CarArrivalEpisode? {
        val existing = qualifiedEpisode
        return if (existing == null) {
            startQualifiedEpisode(identity, at)
        } else {
            updateQualifiedEpisode(existing, identity, at)
        }
    }

    private fun updateQualifiedEpisode(
        episode: CarArrivalEpisode,
        identity: CarContinuityIdentity,
        at: Duration,
    ): CarArrivalEpisode? {
        if (episode.closed || episode.identity != identity) return null
        val earlierStart = minOf(episode.startedAt, safetyPausedAt ?: at, at)
        if (earlierStart >= episode.startedAt) return episode
        val earlier = episode.copy(
            startedAt = earlierStart,
            deadline = earlierStart + QUALIFIED_ARRIVAL_WINDOW,
        )
        qualifiedEpisode = earlier
        revisePendingArrivalDeadline(identity, earlier)
        return earlier
    }

    private fun revisePendingArrivalDeadline(identity: CarContinuityIdentity, episode: CarArrivalEpisode) {
        val focus = focusCandidate
        if (focus != null && focus.phase == Phase.Arrival && focus.identity == identity) {
            focusCandidate = focus.copy(at = episode.startedAt, deadline = episode.deadline)
        }
        val arrival = firstArrival
        if (arrival != null && arrival.identity == identity) {
            firstArrival = arrival.copy(deadline = episode.deadline)
        }
        val recovery = activeRecovery
        if (recovery != null && recovery.phase == Phase.Arrival && recovery.outputId == identity.outputId) {
            activeRecovery = null
        }
    }

    private fun startQualifiedEpisode(identity: CarContinuityIdentity, at: Duration): CarArrivalEpisode? {
        if (!identity.trustedAtPlayback) return null
        if (currentPlayingHeadset(identity.generation, identity.explicitSelectionSequence) != identity) return null
        if (!trustedIdentity(identity.outputId)) return null
        val startedAt = minOf(safetyPausedAt ?: at, at)
        return CarArrivalEpisode(
            identity = identity,
            id = ++nextQualifiedEpisodeId,
            startedAt = startedAt,
            deadline = startedAt + QUALIFIED_ARRIVAL_WINDOW,
        ).also {
            qualifiedEpisode = it
        }
    }

    private fun issueArrivalRecovery(identity: CarContinuityIdentity, deadline: Duration?): Target =
        identity.target(Phase.Arrival, deadline).let { base ->
            val episode = qualifiedEpisode?.takeIf { it.identity == identity }
            base.copy(
                episodeId = episode?.id ?: 0,
                attemptOrdinal = episode?.attempts?.nextAttemptOrdinal ?: 1,
            )
        }.also { target ->
            activeRecovery = target
            qualifiedEpisode?.takeIf { it.identity == identity }?.let { episode ->
                episode.attempts.issue(target)
            }
        }

    private fun finishConfirmedArrival(generation: Long?, sequence: Long, at: Duration? = null) {
        val episode = qualifiedEpisode ?: return
        if (at != null && at < episode.deadline) return
        if (!episode.attempts.confirmed || !episode.identity.matches(null, generation, sequence)) return
        if (!trustedIdentity(episode.identity.outputId)) return
        // Retire the bounded arrival budget, retaining measured playback for a fresh departure pair.
        closeQualifiedArrivalEpisode()
    }

    private fun closeQualifiedArrivalEpisode() {
        val episode = qualifiedEpisode ?: return
        qualifiedEpisode = episode.copy(closed = true)
        safetyPausedAt = null
        if (activeRecovery?.deadline != null) activeRecovery = null
    }

    private fun existingQualifiedClaim(
        target: Target,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
    ): Decision {
        val reason = evidence.targetReason(target, currentGeneration, explicitSelectionSequence, now())
        return if (activeRecovery === target && reason == null) {
            Decision(target.phase, Status.Ready, Reason.BoundaryMatchedFocusLoss, target)
        } else {
            Decision(target.phase, Status.Rejected, reason ?: Reason.CandidateInvalidated)
        }
    }

    private companion object {
        /**
         * Arrival was measured at two to four seconds. The same conservative bound limits how long a focus
         * event may wait for its car-specific lifecycle boundary; a generic focus event never becomes authority
         * merely because time passed.
         */
        val DEFAULT_PAIRING_WINDOW: Duration = 6.seconds
        val QUALIFIED_ARRIVAL_WINDOW: Duration = 30.seconds
    }
}
