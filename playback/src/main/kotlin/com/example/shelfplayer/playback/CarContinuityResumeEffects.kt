package com.example.shelfplayer.playback

import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.common.log.LogField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * One recovery-to-Play transaction, using the existing CarContinuityRecoveryCoordinator's claim.
 *
 * Not another recovery authority or player: all generation and one-shot decisions still live in
 * the coordinator; AudioOutputRouter owns output requests and measured AudioTrack safety.
 * PlaybackService provides its one ExoPlayer and owns the MediaLibrarySession/lifecycle.
 * Owns the service-scoped lifetime of route-hold/recovery effects, exclusively on the existing
 * service/Media3 application dispatcher. The pending Job is work, not another recovery claim.
 */
internal class CarContinuityResumeEffects(
    private val recovery: CarContinuityRecoveryCoordinator,
    private val outputs: AudioOutputRouter,
    private val owner: RouteHeardOwnership,
    private val trace: (String, List<LogField>) -> Unit,
    private val currentPlayer: () -> ExoPlayer?,
    private val selectionSequence: () -> Long,
    private val snapshot: () -> List<LogField>,
    private val resumePlayer: (ExoPlayer, () -> Boolean, () -> Unit) -> Boolean,
    private val scope: CoroutineScope,
    private val synchronizeOutputIntent: () -> Unit = {},
    private val onOutputsChanged: () -> Unit = {},
) {
    private var pending: Job? = null
    private var pendingTarget: CarArrivalResumeGate.Target? = null

    fun onArrival(decision: CarArrivalResumeGate.Decision?) = scheduleBoundary(decision, preserveHeadset = true)

    fun onDeparture(decision: CarArrivalResumeGate.Decision) = scheduleBoundary(decision, preserveHeadset = false)

    fun onRecoveryReady(target: CarArrivalResumeGate.Target) {
        if (!recovery.isCurrent(target, owner.currentGeneration, selectionSequence())) return
        if (!scope.isActive) {
            recovery.discard(target)
            return
        }
        if (pending?.isActive == true && pendingTarget === target) return
        launchWork(target) { recoverAndResume(target) }
    }

    /** New listener/book/session intent stops suspended work immediately, without clearing a successor's claim. */
    fun cancelPending() {
        val target = pendingTarget
        pending?.cancel()
        pending = null
        pendingTarget = null
        if (target != null) recovery.discard(target)
    }

    private fun scheduleBoundary(decision: CarArrivalResumeGate.Decision?, preserveHeadset: Boolean) {
        // Route observation stays independent of listener intent, even for duplicate controller callbacks.
        outputs.resettle()
        val target = decision?.target?.takeIf { decision.status == CarArrivalResumeGate.Status.Ready }
        if (target != null) {
            onRecoveryReady(target)
            return
        }
        // A later controller bind is not permission to replace a Ready focus/projection transaction.
        if (pending?.isActive == true && pendingTarget?.let {
                recovery.isCurrent(it, owner.currentGeneration, selectionSequence())
            } == true
        ) {
            return
        }
        launchWork(null) {
            if (preserveHeadset) holdHeadsetAgainstCar()
        }
    }

    private fun launchWork(target: CarArrivalResumeGate.Target?, action: suspend () -> Unit) {
        cancelPending()
        // LAZY installs the handle before MainImmediate can run, or Play callbacks could cancel the old Job.
        lateinit var work: Job
        work = scope.launch(start = CoroutineStart.LAZY) {
            try {
                currentCoroutineContext().ensureActive()
                action()
                onOutputsChanged()
            } finally {
                if (target != null) recovery.discard(target)
                if (pending === work) {
                    pending = null
                    pendingTarget = null
                }
            }
        }
        pending = work
        pendingTarget = target
        work.start()
    }

    /** Car binding alone preserves the heard headset; it never authorizes Play. */
    private suspend fun holdHeadsetAgainstCar() {
        val current = currentPlayer()?.takeIf { it.mediaItemCount > 0 }
        if (current == null) {
            owner.onQueueEmptied()
            recovery.cancelAll()
            return
        }
        synchronizeOutputIntent()
        currentCoroutineContext().ensureActive()
        val available = outputs.outputs.value
        val hold = owner.headsetForCar(available) ?: return
        val healthyHeadset = current.isPlaying && headsetAlreadyActive(hold, available)
        if (current.playWhenReady || healthyHeadset) {
            outputs.protectPlayingHeadset(hold)
            log(
                if (healthyHeadset) {
                    "Car lifecycle preserved active headset without route reassertion"
                } else {
                    "Car lifecycle left active playback intent with Media3"
                },
            )
        } else {
            val generation = owner.currentGeneration
            val sequence = selectionSequence()
            val reasserted = outputs.reassert(hold)
            currentCoroutineContext().ensureActive()
            if (generation != owner.currentGeneration || sequence != selectionSequence()) return
            if (reasserted && outputs.outputs.value.any { it.id == hold && it.isHeadsetCandidate }) {
                logHeldHeadset(hold)
            }
        }
    }

    private suspend fun recoverCarContinuityHeadset(target: CarArrivalResumeGate.Target): String? {
        val held = recovery.secure(
            target = target,
            outputs = outputs.outputs,
            selectedId = outputs.selectedId,
            currentGeneration = { owner.currentGeneration },
            selectionSequence = selectionSequence,
            onEvent = { event -> logCarContinuityRouteEvent(target, event) },
            reassert = outputs::reassert,
        )
        if (held == null) {
            log(
                "Car lifecycle continuity did not secure the headset",
                fields = listOf(
                    LogField.Public("phase", target.phase.name),
                    LogField.Public("kind", target.outputId.substringBefore(':')),
                    LogField.Public("reason", currentContinuityInvalidation(target)),
                ) + snapshot(),
            )
            // A paused track cannot report its actual route. If Android Auto exposes only
            // REMOTE_SUBMIX, allow a bounded MUTED playback probe of the original exact
            // headset. The router must prove this player's sink before it unmutes.
            if (recovery.isCurrent(
                    target,
                    owner.currentGeneration,
                    selectionSequence(),
                ) && outputs.canProbeHeldHeadset(target.outputId)
            ) {
                log("Car lifecycle will verify original headset silently")
                return target.outputId
            }
            recovery.discard(target)
            return null
        }
        logHeldHeadset(held, target.phase)
        return held
    }

    fun logHeldHeadset(hold: String, phase: CarArrivalResumeGate.Phase? = null) {
        log(
            if (phase == null) {
                "A car connected and the book was held in the headset"
            } else {
                "Car lifecycle continuity secured the headset"
            },
            fields = listOf(
                LogField.Public("phase", phase?.name ?: "route-hold"),
                LogField.Public("kind", hold.substringBefore(':')),
            ) + snapshot(),
        )
    }

    private fun logCarContinuityRouteEvent(target: CarArrivalResumeGate.Target, event: CarArrivalRouteRecovery.Event) {
        log(
            "Car lifecycle route recovery changed",
            fields = listOf(
                LogField.Public("phase", target.phase.name),
                LogField.Public("event", event.name),
                LogField.Public("kind", target.outputId.substringBefore(':')),
            ) + snapshot(),
        )
    }

    private fun currentContinuityInvalidation(target: CarArrivalResumeGate.Target): String =
        recovery.invalidationReason(
            target,
            owner.currentGeneration,
            selectionSequence(),
        )

    suspend fun recoverAndResume(target: CarArrivalResumeGate.Target) {
        // Media3 can hold playWhenReady=true while temporarily suppressing audible playback.
        // It owns focus restoration in that state; issuing another Play or reasserting the output
        // would only disturb a player that is already committed to continuing.
        try {
            val recoveryJob = currentCoroutineContext().job
            currentCoroutineContext().ensureActive()
            if (currentPlayer()?.playWhenReady == true) {
                log("Car lifecycle left active playback intent with Media3")
                return
            }
            val heldHeadset = recoverCarContinuityHeadset(target)
            currentCoroutineContext().ensureActive()
            resumeAfterCarContinuity(target, heldHeadset) { current ->
                recoveryJob.isActive && owner.currentGeneration == target.generation &&
                    selectionSequence() == target.explicitSelectionSequence &&
                    currentPlayer() === current && current.mediaItemCount > 0
            }
        } finally {
            recovery.discard(target)
        }
    }

    /**
     * Issues one Play only after the exact target is secured and the final generation/intent guard passes.
     *
     * The log says Play was issued rather than claiming playback resumed. Actual audio movement is logged from
     * onIsPlayingChanged, which is the first service callback that can truthfully make that claim.
     */
    private fun resumeAfterCarContinuity(
        target: CarArrivalResumeGate.Target,
        heldHeadset: String?,
        stillAuthorized: (ExoPlayer) -> Boolean,
    ) {
        val current = currentPlayer()
        val precheck = CarContinuityPlayPrecheck.decide(
            hasPlayer = current != null,
            mediaItemCount = current?.mediaItemCount ?: 0,
            playWhenReady = current?.playWhenReady == true,
        )
        val assessment = recovery.assess(
            target = target,
            precheck = precheck,
            currentGeneration = owner.currentGeneration,
            selectionSequence = selectionSequence(),
            securedHeadsetId = heldHeadset,
        )
        if (precheck != CarContinuityPlayPrecheck.Proceed) {
            if (precheck == CarContinuityPlayPrecheck.AlreadyPlaying) {
                log(
                    "Car lifecycle continuity skipped Play because playback intent was already active",
                    fields = listOf(LogField.Public("phase", target.phase.name)) + snapshot(),
                )
            }
            return
        }
        checkNotNull(current) { "Proceed implies a player" }
        val result = checkNotNull(assessment.consumed)
        log(
            "Car lifecycle continuity final eligibility checked",
            fields = listOf(
                LogField.Public("phase", target.phase.name),
                LogField.Public("eligible", result.accepted),
                LogField.Public("reason", result.reason.name),
                LogField.Public("kind", target.outputId.substringBefore(':')),
            ) + snapshot(),
        )
        if (!result.accepted) {
            recovery.clearAwaiting(target)
            return
        }

        // A headset that stayed uniquely active can satisfy route recovery without reassertion.
        // Preserve that exact original target as the safety guard before the muted probe.
        if (heldHeadset != null && headsetAlreadyActive(heldHeadset, outputs.outputs.value)) {
            outputs.protectPlayingHeadset(heldHeadset)
        }
        // An AudioManager preference is NOT proof of a physical route. Only the playing
        // AudioTrack may unmute this one-shot recovery; other routes fail closed.
        if (heldHeadset == null || !outputs.beginMutedHeadsetProbe(heldHeadset)) {
            recovery.clearAwaiting(target)
            log("Car lifecycle refused unverified headset probe")
            return
        }
        if (!resumePlayer(current, { stillAuthorized(current) }) { recovery.notePlayIssued(target) }) {
            recovery.clearAwaiting(target)
            return
        }
        log(
            "Car lifecycle continuity issued Play",
            fields = listOf(
                LogField.Public("phase", target.phase.name),
                LogField.Public("kind", target.outputId.substringBefore(':')),
            ) + snapshot(),
        )
    }

    /**
     * The service owns Android Auto correlation and redaction. Recovery effects emit only
     * allow-listed fields through this callback, never owning a second diagnostic timeline.
     */
    private fun log(message: String, fields: List<LogField> = emptyList()) = trace(message, fields)
}
