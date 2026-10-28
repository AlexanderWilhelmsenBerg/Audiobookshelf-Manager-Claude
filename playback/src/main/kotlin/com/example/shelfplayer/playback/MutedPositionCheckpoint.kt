package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import kotlin.time.Duration

/** A transient held progress value owned by exactly one [HeadsetRouteGuard.Probe]. */
internal data class MutedPositionCheckpoint(
    val item: MediaItem,
    val position: Duration,
    val controllerCommandGeneration: Long,
    val mediaItemGeneration: Long,
    val profileGeneration: Long,
    val bookGeneration: Long?,
    val selectionSequence: Long?,
)

internal enum class MutedProbeCompletion {
    Verified,
    Refused,
    Superseded,
}
