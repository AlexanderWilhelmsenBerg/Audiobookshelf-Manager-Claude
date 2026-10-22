package com.example.shelfplayer.playback

import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.common.log.LogField

/**
 * Service-owned execution boundary for a *single* car recovery claim.
 *
 * The existing CarContinuityRecoveryCoordinator owns eligibility and one-shot consumption,
 * RouteHeardOwnership owns book generation, AudioOutputRouter owns route selection and measured
 * AudioTrack verification, and PlaybackService remains the sole ExoPlayer/MediaSession owner.
 *
 * This class owns no mutable state and launches no coroutines. Calls occur on the existing
 * Media3 service dispatcher. It re-reads the current player after every suspending recovery:
 * a player captured before a network/device wait must never issue Play on a replacement player.
 */
internal class CarContinuityResumeEffects(
    private val recovery: CarContinuityRecoveryCoordinator,
    private val audioOutputs: AudioOutputRouter,
    private val ownership: RouteHeardOwnership,
    private val sleepTimer: SleepTimerController,
    private val currentPlayer: () -> ExoPlayer?,
    private val selectionSequence: () -> Long,
    private val trace: (String, List<LogField>) -> Unit,
    private val snapshotFields: () -> List<LogField>,
) {
    suspend fun recoverAndResume(target: CarArrivalResumeGate.Target) {
        // Media3 can hold playWhenReady=true while temporarily suppressing audible playback.
        // It owns focus restoration; duplicating Play or reasserting the output would disturb it.
        if (currentPlayer()?.playWhenReady == true) {
            log("Car lifecycle left active playback intent with Media3")
            recovery.cancelPending()
            return
        }
        val heldHeadset = recoverHeadset(target)
        resumeIfAuthorized(target, heldHeadset)
    }

    /** An unavailable exact target may only be probed SILENTLY, and must remain generation-bound. */
    private suspend fun recoverHeadset(target: CarArrivalResumeGate.Target): String? {
        val held = recovery.secure(
            target = target,
            outputs = audioOutputs.outputs,
            selectedId = audioOutputs.selectedId,
            currentGeneration = { ownership.currentGeneration },
            selectionSequence = selectionSequence,
            onEvent = { event ->
                log(
                    "Car lifecycle route recovery changed",
                    listOf(
                        LogField.Public("phase", target.phase.name),
                        LogField.Public("event", event.name),
                        LogField.Public("kind", target.outputId.substringBefore(':')),
                    ) + snapshotFields(),
                )
            },
            reassert = audioOutputs::reassert,
        )
        if (held == null) {
            val reason = recovery.invalidationReason(target, ownership.currentGeneration, selectionSequence())
            log(
                "Car lifecycle continuity did not secure the headset",
                listOf(
                    LogField.Public("phase", target.phase.name),
                    LogField.Public("kind", target.outputId.substringBefore(':')),
                    LogField.Public("reason", reason),
                ) + snapshotFields(),
            )
            if (recovery.isCurrent(target, ownership.currentGeneration, selectionSequence()) &&
                audioOutputs.canProbeHeldHeadset(target.outputId)
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

    /** Called by the normal car-arrival hold path as well as a recovered exact headset. */
    fun logHeldHeadset(hold: String, phase: CarArrivalResumeGate.Phase? = null) {
        log(
            if (phase == null) {
                "A car connected and the book was held in the headset"
            } else {
                "Car lifecycle continuity secured the headset"
            },
            listOf(
                LogField.Public("phase", phase?.name ?: "route-hold"),
                LogField.Public("kind", hold.substringBefore(':')),
            ) + snapshotFields(),
        )
    }

    /**
     * Final authorization consumes at most one coordinator claim. Preference acceptance is
     * not physical evidence: only the guarded, muted playing AudioTrack may unmute.
     */
    private fun resumeIfAuthorized(target: CarArrivalResumeGate.Target, heldHeadset: String?) {
        val current = currentPlayer()
        val precheck = CarContinuityPlayPrecheck.decide(
            hasPlayer = current != null,
            mediaItemCount = current?.mediaItemCount ?: 0,
            playWhenReady = current?.playWhenReady == true,
        )
        val assessment = recovery.assess(
            target = target,
            precheck = precheck,
            currentGeneration = ownership.currentGeneration,
            selectionSequence = selectionSequence(),
            securedHeadsetId = heldHeadset,
        )
        if (precheck != CarContinuityPlayPrecheck.Proceed) {
            if (precheck == CarContinuityPlayPrecheck.AlreadyPlaying) {
                log(
                    "Car lifecycle continuity skipped Play because playback intent was already active",
                    listOf(LogField.Public("phase", target.phase.name)) + snapshotFields(),
                )
            }
            return
        }
        checkNotNull(current) { "Proceed implies a player" }
        val result = checkNotNull(assessment.consumed)
        log(
            "Car lifecycle continuity final eligibility checked",
            listOf(
                LogField.Public("phase", target.phase.name),
                LogField.Public("eligible", result.accepted),
                LogField.Public("reason", result.reason.name),
                LogField.Public("kind", target.outputId.substringBefore(':')),
            ) + snapshotFields(),
        )
        if (!result.accepted) {
            recovery.clearAwaiting()
            return
        }

        if (heldHeadset != null && headsetAlreadyActive(heldHeadset, audioOutputs.outputs.value)) {
            audioOutputs.protectPlayingHeadset(heldHeadset)
        }
        if (heldHeadset == null || !audioOutputs.beginMutedHeadsetProbe(heldHeadset)) {
            recovery.clearAwaiting()
            log("Car lifecycle refused unverified headset probe")
            return
        }
        recovery.notePlayIssued(target)
        log(
            "Car lifecycle continuity issued Play",
            listOf(
                LogField.Public("phase", target.phase.name),
                LogField.Public("kind", target.outputId.substringBefore(':')),
            ) + snapshotFields(),
        )
        sleepTimer.onServiceResumeIntent()
        current.play()
    }

    private fun log(message: String, fields: List<LogField> = emptyList()) = trace(message, fields)
}
