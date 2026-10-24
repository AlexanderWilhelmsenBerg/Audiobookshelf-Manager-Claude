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
        gate.observePlayingHeadset(
            inputs.heardRoute,
            inputs.headsetId,
            inputs.generation,
            inputs.selectionSequence,
        )
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

    fun invalidationReason(target: CarArrivalResumeGate.Target, generation: Long?, selectionSequence: Long): String =
        when {
            generation != target.generation -> "generation-changed"
            selectionSequence != target.explicitSelectionSequence -> "explicit-selection-changed"
            else -> "target-unavailable-or-candidate-invalidated"
        }

    private var awaiting: CarArrivalResumeGate.Target? = null
    val isPlayAwaiting: Boolean get() = awaiting != null

    fun cancelPending() {
        // Media3 invokes playWhenReady before isPlaying. Do not erase the diagnostic
        // claim on our own successful automatic Play before audible playback is observed.
        gate.cancelPending()
    }

    fun cancelAll() {
        gate.cancelAll()
        awaiting = null
    }

    fun playbackBecameActive(generation: Long?, selectionSequence: Long): CarArrivalResumeGate.Target? {
        val candidate = awaiting
        awaiting = null
        return candidate?.takeIf {
            it.generation == generation && it.explicitSelectionSequence == selectionSequence
        }
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
        awaiting = target
    }

    suspend fun secure(
        target: CarArrivalResumeGate.Target,
        outputs: StateFlow<List<AudioOutput>>,
        selectedId: StateFlow<String?>,
        currentGeneration: () -> Long?,
        selectionSequence: () -> Long,
        onEvent: (CarArrivalRouteRecovery.Event) -> Unit = {},
        reassert: suspend (String) -> Boolean,
    ): String? = routeRecovery.secure(
        target = target,
        outputs = outputs,
        selectedId = selectedId,
        isStillEligible = { gate.isCurrent(target, currentGeneration(), selectionSequence()) },
        onEvent = onEvent,
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
            Assessment(precheck)
        }

        CarContinuityPlayPrecheck.EmptyQueue -> {
            if (gate.cancelRecovery(target)) gate.cancelAll()
            clearAwaiting(target)
            Assessment(precheck)
        }

        CarContinuityPlayPrecheck.Proceed -> {
            val consumed = gate.consumeRecovery(target, currentGeneration, securedHeadsetId, selectionSequence)
            if (!consumed.accepted) clearAwaiting(target)
            Assessment(precheck, consumed)
        }
    }
}
