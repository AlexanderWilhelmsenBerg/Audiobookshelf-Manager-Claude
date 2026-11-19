package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * One recovery episode authority joining car-boundary policy, exact-route recovery, and final Play eligibility.
 * No ExoPlayer, audio-focus, or MediaSession ownership is introduced here.
 */
internal class CarContinuityRecoveryCoordinator(
    val gate: CarArrivalResumeGate,
    private val routeRecovery: CarArrivalRouteRecovery = CarArrivalRouteRecovery(),
) {
    internal sealed interface PlayOutcome {
        val episodeId: Long
        val attemptOrdinal: Int
        val phase: CarArrivalResumeGate.Phase

        data class Issued(
            override val episodeId: Long,
            override val attemptOrdinal: Int,
            override val phase: CarArrivalResumeGate.Phase,
        ) : PlayOutcome

        data class AudibleConfirmed(
            override val episodeId: Long,
            override val attemptOrdinal: Int,
            override val phase: CarArrivalResumeGate.Phase,
        ) : PlayOutcome

        data class Refused(
            override val episodeId: Long,
            override val attemptOrdinal: Int,
            override val phase: CarArrivalResumeGate.Phase,
        ) : PlayOutcome

        data class Stale(
            override val episodeId: Long,
            override val attemptOrdinal: Int,
            override val phase: CarArrivalResumeGate.Phase,
        ) : PlayOutcome

        data class Cancelled(
            override val episodeId: Long,
            override val attemptOrdinal: Int,
            override val phase: CarArrivalResumeGate.Phase,
        ) : PlayOutcome
    }

    internal data class Assessment(
        val precheck: CarContinuityPlayPrecheck,
        val consumed: CarArrivalResumeGate.ConsumeResult? = null,
    ) {
        val mayPlay: Boolean get() = precheck == CarContinuityPlayPrecheck.Proceed && consumed?.accepted == true
    }

    /** Snapshot of service-owned truth. No second command, player, or route generation is introduced. */
    internal data class Inputs(
        val heardRoute: RouteHeardOwnership.HeardRoute?,
        val headsetId: String?,
        val generation: Long?,
        val selectionSequence: Long,
        val carConnected: Boolean,
    )

    fun observePlayingHeadset(inputs: Inputs) {
        val confirmed = gate.observePlayingHeadset(
            inputs.heardRoute,
            inputs.headsetId,
            inputs.generation,
            inputs.selectionSequence,
        )
        confirmed?.let { target ->
            onPlayOutcome(PlayOutcome.AudibleConfirmed(target.episodeId, target.attemptOrdinal, target.phase))
        }
    }

    /** Correlate either Media3 platform interruption with the same car-specific boundary policy. */
    fun platformInterruption(at: Duration, inputs: Inputs, focusLoss: Boolean): CarArrivalResumeGate.Decision {
        val interrupted = if (focusLoss) gate::onAudioFocusLoss else gate::onAudioBecomingNoisy
        return interrupted(
            at,
            inputs.heardRoute,
            inputs.headsetId,
            inputs.generation,
            inputs.selectionSequence,
            inputs.carConnected,
        )
    }

    fun carArrival(at: Duration, generation: Long?, sequence: Long): CarArrivalResumeGate.Decision =
        gate.onCarArrival(at, generation, sequence)

    fun carDeparture(
        at: Duration,
        generation: Long?,
        sequence: Long,
        physicalProjection: Boolean,
    ): CarArrivalResumeGate.Decision = if (physicalProjection) {
        gate.onProjectionDeparture(at, generation, sequence)
    } else {
        gate.onCarDeparture(at, generation, sequence)
    }

    fun isCurrent(target: CarArrivalResumeGate.Target, generation: Long?, selectionSequence: Long): Boolean =
        gate.isCurrent(target, generation, selectionSequence)

    fun hasPendingHeadsetReturn(generation: Long?, selectionSequence: Long): Boolean =
        gate.pendingHeadsetReturn(generation, selectionSequence) != null

    fun remainingWindow(target: CarArrivalResumeGate.Target): Duration? = gate.remainingWindow(target)

    fun episodeValid(target: CarArrivalResumeGate.Target): Boolean = gate.episodeValid(target)

    fun invalidationReason(target: CarArrivalResumeGate.Target, generation: Long?, selectionSequence: Long): String =
        when {
            generation != target.generation -> "generation-changed"
            selectionSequence != target.explicitSelectionSequence -> "explicit-selection-changed"
            else -> "target-unavailable-or-candidate-invalidated"
        }

    private var awaiting: CarArrivalResumeGate.Target? = null
    val isPlayAwaiting: Boolean get() = awaiting != null

    fun cancelPending() {
        // Media3 invokes playWhenReady before isPlaying. Retain the issued-attempt correlation
        // until active playback and the exact actual route can confirm it.
        gate.cancelPending()
    }

    fun cancelAll() {
        gate.cancelAll()
        val cancelled = awaiting
        awaiting = null
        cancelled?.let { target ->
            onPlayOutcome(PlayOutcome.Cancelled(target.episodeId, target.attemptOrdinal, target.phase))
        }
    }

    fun routeSafetyPause(at: Duration, inputs: Inputs): CarArrivalResumeGate.Decision? {
        val rearmed = gate.onRouteSafetyPause(
            at = at,
            headsetId = inputs.headsetId,
            currentGeneration = inputs.generation,
            explicitSelectionSequence = inputs.selectionSequence,
            carConnected = inputs.carConnected,
        )
        awaiting = null
        return rearmed
    }

    fun playbackBecameActive(generation: Long?, selectionSequence: Long): CarArrivalResumeGate.Target? {
        val candidate = awaiting
        awaiting = null
        val confirmed = candidate?.takeIf {
            it.generation == generation && it.explicitSelectionSequence == selectionSequence
        }
        if (confirmed != null && gate.confirmRecoveryPlayback(confirmed)) {
            onPlayOutcome(PlayOutcome.AudibleConfirmed(confirmed.episodeId, confirmed.attemptOrdinal, confirmed.phase))
        }
        return confirmed
    }

    fun clearAwaiting() {
        awaiting = null
    }

    fun clearAwaiting(target: CarArrivalResumeGate.Target) {
        if (awaiting === target) awaiting = null
    }

    /** Effect completion/cancellation may retire its own episode, never a newer one. */
    fun discard(target: CarArrivalResumeGate.Target) {
        gate.cancelRecovery(target)
    }

    fun notePlayIssued(target: CarArrivalResumeGate.Target) {
        gate.markRecoveryPlayIssued(target)
        awaiting = target
        onPlayOutcome(PlayOutcome.Issued(target.episodeId, target.attemptOrdinal, target.phase))
    }

    /** Optional diagnostics sink; events contain only bounded policy metadata, never the route identity. */
    var onPlayOutcome: (PlayOutcome) -> Unit = {}

    suspend fun secure(
        target: CarArrivalResumeGate.Target,
        outputs: StateFlow<List<AudioOutput>>,
        selectedId: StateFlow<String?>,
        currentGeneration: () -> Long?,
        selectionSequence: () -> Long,
        measured: (String) -> Boolean,
        canProbe: (String) -> Boolean = { false },
        onEvent: (CarArrivalRouteRecovery.Event) -> Unit = {},
        reassert: suspend (String) -> Boolean,
    ): String? = routeRecovery.secure(
        target = target,
        outputs = outputs,
        selectedId = selectedId,
        isStillEligible = { gate.isCurrent(target, currentGeneration(), selectionSequence()) },
        measured = measured,
        canProbe = canProbe,
        onEvent = onEvent,
        recoveryWindowOverride = gate.remainingWindow(target),
        reassert = reassert,
    )

    /** Exactly one final claim consumption; rejected and stale work cannot authorize another Play. */
    fun assess(
        target: CarArrivalResumeGate.Target,
        precheck: CarContinuityPlayPrecheck,
        currentGeneration: Long?,
        selectionSequence: Long,
        securedHeadsetId: String?,
    ): Assessment = when (precheck) {
        CarContinuityPlayPrecheck.NoPlayer,
        CarContinuityPlayPrecheck.AlreadyPlaying,
        -> {
            discard(target)
            clearAwaiting(target)
            onPlayOutcome(PlayOutcome.Refused(target.episodeId, target.attemptOrdinal, target.phase))
            Assessment(precheck)
        }

        CarContinuityPlayPrecheck.EmptyQueue -> {
            if (gate.cancelRecovery(target)) gate.cancelAll()
            clearAwaiting(target)
            onPlayOutcome(PlayOutcome.Refused(target.episodeId, target.attemptOrdinal, target.phase))
            Assessment(precheck)
        }

        CarContinuityPlayPrecheck.Proceed -> {
            val consumed = gate.consumeRecovery(target, currentGeneration, securedHeadsetId, selectionSequence)
            if (!consumed.accepted) clearAwaiting(target)
            if (!consumed.accepted) {
                val outcome = if (consumed.reason in setOf(
                        CarArrivalResumeGate.Reason.CandidateInvalidated,
                        CarArrivalResumeGate.Reason.GenerationChanged,
                        CarArrivalResumeGate.Reason.ExplicitSelectionChanged,
                    )
                ) {
                    PlayOutcome.Stale(target.episodeId, target.attemptOrdinal, target.phase)
                } else {
                    PlayOutcome.Refused(target.episodeId, target.attemptOrdinal, target.phase)
                }
                onPlayOutcome(outcome)
            }
            Assessment(precheck, consumed)
        }
    }
}
