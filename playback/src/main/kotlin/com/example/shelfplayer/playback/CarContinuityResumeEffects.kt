package com.example.shelfplayer.playback

import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.common.log.LogField

/**
 * One recovery-to-Play transaction, using the existing CarContinuityRecoveryCoordinator's claim.
 *
 * Not another recovery authority or player: all generation and one-shot decisions still live in
 * the coordinator; AudioOutputRouter owns output requests and measured AudioTrack safety.
 * PlaybackService provides its one ExoPlayer and owns the MediaLibrarySession/lifecycle.
 * Invoked exclusively on the existing service/Media3 application dispatcher.
 */
internal class CarContinuityResumeEffects(
    private val recovery: CarContinuityRecoveryCoordinator,
    private val outputs: AudioOutputRouter,
    private val owner: RouteHeardOwnership,
    private val trace: (String, List<LogField>) -> Unit,
    private val currentPlayer: () -> ExoPlayer?,
    private val selectionSequence: () -> Long,
    private val snapshot: () -> List<LogField>,
    private val onServiceResumeIntent: () -> Unit,
) {
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
            recovery.cancelPending()
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
        if (currentPlayer()?.playWhenReady == true) {
            log("Car lifecycle left active playback intent with Media3")
            recovery.cancelPending()
            return
        }
        val heldHeadset = recoverCarContinuityHeadset(target)
        resumeAfterCarContinuity(target, heldHeadset)
    }

    /**
     * Issues one Play only after the exact target is secured and the final generation/intent guard passes.
     *
     * The log says Play was issued rather than claiming playback resumed. Actual audio movement is logged from
     * onIsPlayingChanged, which is the first service callback that can truthfully make that claim.
     */
    private fun resumeAfterCarContinuity(target: CarArrivalResumeGate.Target, heldHeadset: String?) {
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
            recovery.clearAwaiting()
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
            recovery.clearAwaiting()
            log("Car lifecycle refused unverified headset probe")
            return
        }
        recovery.notePlayIssued(target)
        log(
            "Car lifecycle continuity issued Play",
            fields = listOf(
                LogField.Public("phase", target.phase.name),
                LogField.Public("kind", target.outputId.substringBefore(':')),
            ) + snapshot(),
        )
        onServiceResumeIntent()
        current.play()
    }

    /**
     * The service owns Android Auto correlation and redaction. Recovery effects emit only
     * allow-listed fields through this callback, never owning a second diagnostic timeline.
     */
    private fun log(message: String, fields: List<LogField> = emptyList()) = trace(message, fields)
}
