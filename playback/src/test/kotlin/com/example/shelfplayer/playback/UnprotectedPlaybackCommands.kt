package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/** Explicit command double for policy tests that do not model Android output protection. */
internal class UnprotectedPlaybackCommands : PlaybackCommands {
    override fun prepare(current: Player, stillAuthorized: () -> Boolean, holdForSeek: Boolean): Boolean {
        if (!stillAuthorized() || (holdForSeek && current.playWhenReady)) return false
        current.prepare()
        return stillAuthorized()
    }

    override fun play(current: Player, stillAuthorized: () -> Boolean, beforeRawPlay: () -> Unit): Boolean {
        if (!stillAuthorized()) return false
        beforeRawPlay()
        if (!stillAuthorized()) return false
        current.play()
        return true
    }

    override fun installAndPrepare(
        current: Player,
        item: MediaItem,
        startPositionMs: Long,
        authorizedBeforeInstall: () -> Boolean,
        stillAuthorizedAfterInstall: () -> Boolean,
        startPlaying: Boolean,
    ): Boolean {
        if (!authorizedBeforeInstall()) return false
        current.setMediaItem(item, startPositionMs)
        if (!stillAuthorizedAfterInstall()) return false
        current.prepare()
        return !startPlaying || play(current, stillAuthorizedAfterInstall)
    }
}
