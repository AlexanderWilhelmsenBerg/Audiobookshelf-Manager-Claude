package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlin.time.Duration

/** Router policy fixtures isolate route effects. Actual service fixtures install full position protection. */
internal fun routingOnlyPositionEffects(): PositionCheckpointEffects = object : PositionCheckpointEffects {
    override fun authority() = MutedPositionEffects.Context(0, 0, 0)

    override fun capture(probe: HeadsetRouteGuard.Probe) = MutedPositionCheckpoint(
        probe.player.currentMediaItem ?: MediaItem.EMPTY,
        Duration.ZERO,
        0,
        0,
        0,
        null,
        null,
    )

    override suspend fun restoreForProof(probe: HeadsetRouteGuard.Probe) = true

    override fun restoreCurrent(probe: HeadsetRouteGuard.Probe) = true

    override fun position(target: Player): Duration = target.bookPosition()

    override fun beforeTransfer(): Boolean = true

    override fun bindTransferScope(
        probe: HeadsetRouteGuard.Probe,
        request: OutputIntentOwner.TransferRequest,
    ): Boolean {
        probe.positionCheckpoint = probe.positionCheckpoint?.copy(
            selectionSequence = request.selection.sequence,
            bookGeneration = request.bookGeneration,
        )
        return true
    }

    override fun isAuthorized(probe: HeadsetRouteGuard.Probe): Boolean = true

    override fun isSettled(probe: HeadsetRouteGuard.Probe): Boolean = true

    override fun restorePausedProbe(probe: HeadsetRouteGuard.Probe) = Unit
}
