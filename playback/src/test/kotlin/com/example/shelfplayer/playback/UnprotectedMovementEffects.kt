package com.example.shelfplayer.playback

import androidx.media3.common.Player

/** Explicit policy-test collaboration; production uses the service's Probe-backed effects. */
internal fun unprotectedMovementEffects(player: Player): PlaybackMovementEffects = object : PlaybackMovementEffects {
    override fun before(command: PlaybackMovement): () -> Unit = {}

    override fun positionMs(): Long = player.currentPosition
}
