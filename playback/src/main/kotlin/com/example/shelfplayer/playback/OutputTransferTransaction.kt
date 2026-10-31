package com.example.shelfplayer.playback

import androidx.media3.common.Player
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

internal enum class OutputTransferStage { AwaitingDestination, RestoringPosition, VerifyingOutput }

/**
 * One ordered completion of the existing probe. No independent job, retry owner or mutable request.
 * A seek may replace the output, so only post-restoration evidence can complete this transaction.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OutputTransferTransaction(
    private val probe: HeadsetRouteGuard.Probe,
    private val positions: PositionCheckpointEffects,
    private val proof: (HeadsetRouteGuard.Probe) -> MutedProbeCompletion?,
    private val refuse: (HeadsetRouteGuard.Probe) -> MutedProbeCompletion,
    private val stage: (HeadsetRouteGuard.Probe, OutputTransferStage) -> Unit,
) {
    private val checkpoint = probe.positionCheckpoint

    private fun isCurrent(): Boolean = probe.positionCheckpoint === checkpoint && positions.isAuthorized(probe)

    suspend fun complete(): MutedProbeCompletion {
        var finished = false
        return try {
            completeCurrent().also { finished = true }
        } finally {
            // Cancellation cannot restore a superseded player/item/command/checkpoint.
            if (!finished && isCurrent()) positions.restoreCurrent(probe)
        }
    }

    private suspend fun completeCurrent(): MutedProbeCompletion {
        stage(probe, OutputTransferStage.AwaitingDestination)
        val destination = awaitProof()
        if (!destination.permitsAudio) return finishFailure(destination)
        stage(probe, OutputTransferStage.RestoringPosition)
        if (!positions.restoreForProof(probe)) return finishFailure(MutedProbeCompletion.Refused)
        stage(probe, OutputTransferStage.VerifyingOutput)
        val final = awaitProof()
        if (!final.permitsAudio) return finishFailure(final)
        // Never loop from final proof back to a seek. A late output fails at the held position.
        return when {
            !isCurrent() -> MutedProbeCompletion.Superseded
            positions.isSettled(probe) -> final
            else -> refuse(probe)
        }
    }

    private suspend fun awaitProof(): MutedProbeCompletion = withTimeoutOrNull(1.seconds) {
        while (isCurrent()) {
            val player = probe.player
            if (!player.playWhenReady) return@withTimeoutOrNull MutedProbeCompletion.Refused
            if (player.isPlaying && player.playbackState == Player.STATE_READY) {
                val measured = proof(probe)
                if (!isCurrent()) return@withTimeoutOrNull MutedProbeCompletion.Superseded
                if (measured != null) return@withTimeoutOrNull measured
            }
            probe.routeUpdates.receive()
        }
        MutedProbeCompletion.Superseded
    } ?: MutedProbeCompletion.Refused

    private fun finishFailure(result: MutedProbeCompletion): MutedProbeCompletion =
        if (result == MutedProbeCompletion.Superseded || !isCurrent()) {
            MutedProbeCompletion.Superseded
        } else {
            refuse(probe)
        }
}
