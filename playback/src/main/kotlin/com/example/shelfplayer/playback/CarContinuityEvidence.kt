package com.example.shelfplayer.playback

import com.example.shelfplayer.playback.CarArrivalResumeGate.Reason
import com.example.shelfplayer.playback.CarArrivalResumeGate.Target
import kotlin.time.Duration

/** Stateless identity and timing checks shared by arrival and departure; no claims or state are owned here. */
internal class CarContinuityEvidence(
    private val pairingWindow: Duration,
    private val trustedIdentity: (String) -> Boolean,
) {
    data class ArrivalBoundary(
        val at: Duration,
        val identity: CarContinuityIdentity,
        val deadline: Duration?,
        val projectionAt: Duration? = null,
    ) {
        fun confirmProjection(at: Duration): ArrivalBoundary = if (projectionAt ==
            null
        ) {
            copy(projectionAt = at)
        } else {
            this
        }
    }

    fun invalidArrival(boundary: ArrivalBoundary, at: Duration, generation: Long?, selection: Long): Reason? =
        if (boundary.projectionAt?.let { at - it > pairingWindow } == true) {
            Reason.OutsidePairingWindow
        } else {
            invalidReason(boundary.identity, generation, selection, at - boundary.at, at, boundary.deadline)
        }

    fun identityOf(
        heardRoute: RouteHeardOwnership.HeardRoute?,
        headsetId: String?,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
        playingHeadset: CarContinuityIdentity?,
        captureTrust: Boolean = false,
    ): CarContinuityIdentity? = heardRoute
        ?.takeIf { heard ->
            headsetId != null &&
                heard.outputId == headsetId &&
                heard.generation == currentGeneration
        }
        ?.let { heard ->
            CarContinuityIdentity(
                outputId = heard.outputId,
                generation = heard.generation,
                explicitSelectionSequence = explicitSelectionSequence,
                trustedAtPlayback = if (captureTrust) {
                    trustedIdentity(heard.outputId)
                } else {
                    playingHeadset?.takeIf {
                        it.outputId == heard.outputId && it.generation == heard.generation &&
                            it.explicitSelectionSequence == explicitSelectionSequence
                    }?.trustedAtPlayback ?: false
                },
            )
        }

    fun invalidReason(
        identity: CarContinuityIdentity,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
        interval: Duration,
        eventAt: Duration? = null,
        deadline: Duration? = null,
    ): Reason? = when {
        identity.generation != currentGeneration -> Reason.GenerationChanged

        identity.explicitSelectionSequence != explicitSelectionSequence -> Reason.ExplicitSelectionChanged

        deadline != null && eventAt != null && eventAt >= deadline -> Reason.RecoveryExpired

        interval < Duration.ZERO || interval > maxPairInterval(interval, eventAt, deadline) ->
            Reason.OutsidePairingWindow

        else -> null
    }

    /** The pending focus loss is still trusted, in scope and inside its original deadline at [at]. */
    fun supportsInferredArrival(
        identity: CarContinuityIdentity,
        focusAt: Duration,
        deadline: Duration?,
        at: Duration,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
    ): Boolean = trustedIdentity(identity.outputId) &&
        invalidReason(identity, currentGeneration, explicitSelectionSequence, at - focusAt, at, deadline) == null

    fun targetReason(
        target: Target,
        currentGeneration: Long?,
        explicitSelectionSequence: Long,
        at: Duration,
    ): Reason? = when {
        target.generation != currentGeneration -> Reason.GenerationChanged
        target.explicitSelectionSequence != explicitSelectionSequence -> Reason.ExplicitSelectionChanged
        target.deadline != null && at >= target.deadline -> Reason.RecoveryExpired
        target.deadline != null && !trustedIdentity(target.outputId) -> Reason.IdentityUntrusted
        else -> null
    }

    private fun maxPairInterval(interval: Duration, eventAt: Duration?, deadline: Duration?): Duration =
        if (deadline != null && eventAt != null) deadline - (eventAt - interval) else pairingWindow
}
