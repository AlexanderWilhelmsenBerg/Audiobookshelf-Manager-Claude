package com.example.shelfplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.model.playback.AudioOutputRole

/** Retains current playback's projected route through Android's fallback and departure callback. */
@OptIn(UnstableApi::class)
internal class CarOutputSafety(private val outputs: AudioOutputRouter, private val isAudible: (ExoPlayer) -> Boolean) {
    private data class ProjectedPlayback(
        val player: ExoPlayer,
        val bookGeneration: Long,
        val selection: OutputIntentOwner.Selection?,
    ) {
        fun matches(player: ExoPlayer, intent: OutputIntentOwner.Snapshot): Boolean =
            this.player === player && bookGeneration == intent.activeBookGeneration && selection == intent.selection
    }

    private var projectedPlayback: ProjectedPlayback? = null

    fun clear() {
        projectedPlayback = null
    }

    /** Called on the player dispatcher for track changes and before scheduling departure recovery. */
    fun pauseIfLost(
        player: ExoPlayer,
        projectionActive: Boolean,
        projectionDeparted: Boolean,
        beforePause: () -> Unit,
    ): Boolean {
        val intent = outputs.outputIntentOwner.state.value
        val stillCurrent = {
            outputs.isAttachedPlayer(player) && player.playWhenReady && outputs.outputIntentOwner.state.value === intent
        }
        // An explicit endpoint owns its own safety checks, including a muted in-progress transfer.
        if (!stillCurrent() || intent.selection?.destination == OutputIntentOwner.Destination.Output) return false
        projectedPlayback = projectedPlayback?.takeIf { it.matches(player, intent) }
        val observation = outputs.currentTrackObservation()
        val committedCar = intent.hasCommittedCarRoute()
        val verifiedProjection = projectionActive && !committedCar && isVerifiedProjection(player, observation)
        if (!stillCurrent()) return false
        if (verifiedProjection) {
            intent.activeBookGeneration?.let { projectedPlayback = ProjectedPlayback(player, it, intent.selection) }
        }
        val lost = lostOutput(observation?.path, committedCar || projectedPlayback != null, projectionDeparted)
        if (!lost) return false
        clear()
        beforePause()
        return stillCurrent() && outputs.positions.guard.pauseForSafety(player)
    }

    private fun isVerifiedProjection(player: ExoPlayer, first: AudioTrackObservation?): Boolean =
        first?.path == AudioTrackPath.RemoteSubmix && outputs.selectedId.value == null &&
            isAudible(player) && outputs.currentTrackObservation() == first

    private fun lostOutput(actual: AudioTrackPath?, playingCar: Boolean, projectionDeparted: Boolean): Boolean =
        if (projectionDeparted) {
            playingCar || actual == AudioTrackPath.RemoteSubmix
        } else {
            playingCar &&
                hasLocalFallback(actual)
        }

    private fun hasLocalFallback(actual: AudioTrackPath?): Boolean = when (actual) {
        is AudioTrackPath.Endpoint -> outputs.localOutputs.outputs().any {
            it.id == actual.id && it.role != AudioOutputRole.Car
        }

        AudioTrackPath.Multiple -> true

        is AudioTrackPath.RetainedEndpoint,
        AudioTrackPath.RemoteSubmix,
        AudioTrackPath.Unresolved,
        null,
        -> false
    }
}

/** Retain recent proof at the boundary even if Android has already replaced the playing route. */
private fun OutputIntentOwner.Snapshot.hasCommittedCarRoute(): Boolean {
    val current = transfer ?: return false
    val request = current.request
    if (request.selection != selection || request.bookGeneration != activeBookGeneration) return false
    return request.selection.destination == OutputIntentOwner.Destination.Car &&
        (
            current.phase == OutputIntentOwner.TransferPhase.Delegated ||
                current.phase == OutputIntentOwner.TransferPhase.Verified
            )
}
