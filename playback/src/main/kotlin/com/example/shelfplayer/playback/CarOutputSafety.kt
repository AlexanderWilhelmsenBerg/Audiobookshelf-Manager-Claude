package com.example.shelfplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.model.playback.AudioOutputRole

/** The service calls this on the player dispatcher before scheduling any departure recovery. */
@OptIn(UnstableApi::class)
internal fun AudioOutputRouter.pauseIfCarOutputLost(
    player: ExoPlayer,
    projectionDeparted: Boolean,
    beforePause: () -> Unit,
): Boolean {
    val intent = outputIntentOwner.state.value
    val stillCurrent = {
        isAttachedPlayer(player) && player.playWhenReady && outputIntentOwner.state.value === intent
    }
    if (!stillCurrent() || !carOutputLost(intent, projectionDeparted) || !stillCurrent()) return false
    beforePause()
    return stillCurrent() && positions.guard.pauseForSafety(player)
}

private fun AudioOutputRouter.carOutputLost(intent: OutputIntentOwner.Snapshot, projectionDeparted: Boolean): Boolean {
    // A newer explicit endpoint owns its own output checks, including muted in-progress transfers.
    if (intent.selection?.destination == OutputIntentOwner.Destination.Output) return false
    val actual = currentTrackObservation()?.path
    val committedCar = intent.hasCommittedCarRoute()
    if (projectionDeparted) return committedCar || actual == AudioTrackPath.RemoteSubmix
    if (!committedCar) return false
    return when (actual) {
        is AudioTrackPath.Endpoint -> localOutputs.outputs().any {
            it.id == actual.id && it.role != AudioOutputRole.Car
        }

        AudioTrackPath.Multiple -> true

        else -> false
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
