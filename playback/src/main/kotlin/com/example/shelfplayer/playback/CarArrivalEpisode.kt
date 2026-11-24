package com.example.shelfplayer.playback

import kotlin.time.Duration

/** Measured identity scoped to the existing book and listener selection authorities. */
internal data class CarContinuityIdentity(
    val outputId: String,
    val generation: Long,
    val explicitSelectionSequence: Long,
    val trustedAtPlayback: Boolean,
) {
    fun target(phase: CarArrivalResumeGate.Phase, deadline: Duration? = null) = CarArrivalResumeGate.Target(
        outputId,
        generation,
        explicitSelectionSequence,
        phase,
        deadline,
    )

    fun matches(headsetId: String?, generation: Long?, sequence: Long): Boolean =
        (headsetId == null || outputId == headsetId) && this.generation == generation &&
            explicitSelectionSequence == sequence
}

/** An issued target retains its identity when the episode's absolute deadline is tightened. */
internal fun CarArrivalEpisode?.forIssued(target: CarArrivalResumeGate.Target): CarArrivalEpisode? =
    this?.takeIf { !it.closed && it.issuedTarget === target }

/** Immutable episode lifetime with one bounded attempt owner; owned exclusively by the recovery gate. */
internal data class CarArrivalEpisode(
    val identity: CarContinuityIdentity,
    val id: Long,
    val startedAt: Duration,
    val deadline: Duration,
    val attempts: CarArrivalRecoveryAttemptState = CarArrivalRecoveryAttemptState(),
    val closed: Boolean = false,
) {
    val issuedTarget: CarArrivalResumeGate.Target? get() = attempts.issuedTarget
    val unconfirmedTarget: CarArrivalResumeGate.Target? get() = issuedTarget.takeUnless { attempts.confirmed }

    /** Open, for this exact identity, and no automatic Play claimed yet. */
    fun awaitsFirstClaim(candidate: CarContinuityIdentity?): Boolean =
        !closed && identity == candidate && issuedTarget == null

    val blocksNoisyRetry: Boolean get() = attempts.hasUnconfirmedPlay || unconfirmedTarget?.retryAfterFocusLoss == true

    fun rearm(
        at: Duration,
        headsetId: String?,
        generation: Long?,
        sequence: Long,
        carConnected: Boolean,
        trusted: (String) -> Boolean,
    ): CarArrivalResumeGate.Target? {
        // A confirmed inferred arrival can lose its route again before the real Auto bind.
        val arrived = carConnected || attempts.inferredArrival
        if (!arrived || !identity.matches(headsetId, generation, sequence)) return null
        if (!mayRepeat(at) || !trusted(identity.outputId)) return null
        return issueTarget()
    }

    private fun mayRepeat(at: Duration): Boolean =
        !closed && attempts.confirmed && attempts.canClaim && withinLifetime(at)

    fun retryInterruptedPlay(
        at: Duration,
        headsetId: String?,
        generation: Long?,
        sequence: Long,
        carConnected: Boolean,
        trusted: (String) -> Boolean,
    ): CarArrivalResumeGate.Target? {
        // An inferred arrival already stood in for the boundary; its interrupted Play may use the budget.
        val arrived = carConnected || attempts.inferredArrival
        if (!arrived || !identity.matches(headsetId, generation, sequence)) return null
        if (closed || !withinLifetime(at)) return null
        if (!attempts.hasUnconfirmedPlay || !attempts.canClaim || !trusted(identity.outputId)) return null
        return issueTarget(retryAfterFocusLoss = true)
    }

    fun issueTarget(
        retryAfterFocusLoss: Boolean = false,
        inferredArrival: Boolean = false,
    ): CarArrivalResumeGate.Target = identity.target(CarArrivalResumeGate.Phase.Arrival, deadline).copy(
        episodeId = id,
        attemptOrdinal = attempts.nextAttemptOrdinal,
        retryAfterFocusLoss = retryAfterFocusLoss,
        inferredArrival = inferredArrival,
    ).also(attempts::issue)

    fun startingEarlier(identity: CarContinuityIdentity, at: Duration, window: Duration): CarArrivalEpisode? {
        if (closed || this.identity != identity) return null
        return if (at < startedAt) copy(startedAt = at, deadline = at + window) else this
    }

    private fun withinLifetime(at: Duration): Boolean = at >= startedAt && at < deadline
}
