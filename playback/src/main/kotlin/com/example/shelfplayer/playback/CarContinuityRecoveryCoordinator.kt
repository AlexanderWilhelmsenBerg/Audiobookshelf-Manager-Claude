package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import kotlinx.coroutines.flow.StateFlow

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
            cancelPending()
            clearAwaiting()
            Assessment(precheck)
        }

        CarContinuityPlayPrecheck.EmptyQueue -> {
            cancelAll()
            Assessment(precheck)
        }

        CarContinuityPlayPrecheck.Proceed -> {
            val consumed = gate.consumeRecovery(target, currentGeneration, securedHeadsetId, selectionSequence)
            if (!consumed.accepted) awaiting = null
            Assessment(precheck, consumed)
        }
    }
}
