package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlin.time.Duration

/** Router policy fixtures isolate route effects. Actual service fixtures install full position protection. */
internal fun routingOnlyPositionEffects(): PositionCheckpointEffects = object : PositionCheckpointEffects {
    override fun capture(probe: HeadsetRouteGuard.Probe) = MutedPositionCheckpoint(
        MediaItem.EMPTY,
        Duration.ZERO,
        0,
        0,
        0,
        null,
        null,
    )

    override suspend fun complete(probe: HeadsetRouteGuard.Probe) = MutedProbeCompletion.Verified

    override fun position(target: Player): Duration = target.bookPosition()
}
