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
    val blocksNoisyRetry: Boolean get() = attempts.hasUnconfirmedPlay || unconfirmedTarget?.retryAfterFocusLoss == true

    fun rearm(
        at: Duration,
        headsetId: String?,
        generation: Long?,
        sequence: Long,
        carConnected: Boolean,
        trusted: (String) -> Boolean,
    ): CarArrivalResumeGate.Target? {
        if (!carConnected || !identity.matches(headsetId, generation, sequence)) return null
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
        if (!carConnected || !identity.matches(headsetId, generation, sequence)) return null
        if (closed || !withinLifetime(at)) return null
        if (!attempts.hasUnconfirmedPlay || !attempts.canClaim || !trusted(identity.outputId)) return null
        return issueTarget(retryAfterFocusLoss = true)
    }

    fun issueTarget(retryAfterFocusLoss: Boolean = false): CarArrivalResumeGate.Target =
        identity.target(CarArrivalResumeGate.Phase.Arrival, deadline).copy(
            episodeId = id,
            attemptOrdinal = attempts.nextAttemptOrdinal,
            retryAfterFocusLoss = retryAfterFocusLoss,
        ).also(attempts::issue)

    fun startingEarlier(identity: CarContinuityIdentity, at: Duration, window: Duration): CarArrivalEpisode? {
        if (closed || this.identity != identity) return null
        return if (at < startedAt) copy(startedAt = at, deadline = at + window) else this
    }

    private fun withinLifetime(at: Duration): Boolean = at >= startedAt && at < deadline
}
