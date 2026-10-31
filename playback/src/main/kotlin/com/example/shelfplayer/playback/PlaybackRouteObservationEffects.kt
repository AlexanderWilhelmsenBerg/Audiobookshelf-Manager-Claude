package com.example.shelfplayer.playback

import androidx.media3.common.Player

/**
 * Service-scoped positive route observer. It owns only its exact volume-listener identity and the common
 * attached-current-player/audible gate; route, selection, generation and recovery policy remain with callers.
 */
internal class PlaybackRouteObservationEffects(
    private val currentPlayer: () -> Player?,
    private val volumeEffects: PlaybackVolumeEffects,
    private val onAudible: () -> Unit,
) {
    private val muteListener: (PlaybackVolumeEffects.MuteChange) -> Unit = { change ->
        if (!change.muted) observe(change.player)
    }

    fun attach() {
        volumeEffects.addMuteListener(muteListener)
    }

    fun detach() {
        volumeEffects.removeMuteListener(muteListener)
    }

    fun isAudible(target: Player? = currentPlayer()): Boolean {
        if (target == null || currentPlayer() !== target) return false
        val playing = target.isPlaying
        val muted = volumeEffects.isRouteMuted(target)
        return currentPlayer() === target && playing && !muted
    }

    fun observe(target: Player? = currentPlayer()) {
        if (isAudible(target)) onAudible()
    }
}
