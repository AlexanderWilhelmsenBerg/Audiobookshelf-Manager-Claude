package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/** The service-owned boundary for commands that can make the current Media3 item audible. */
internal interface PlaybackCommands {
    fun prepare(current: Player, stillAuthorized: () -> Boolean, holdForSeek: Boolean = false): Boolean

    fun play(current: Player, stillAuthorized: () -> Boolean, beforeRawPlay: () -> Unit = {}): Boolean

    /** Installs a newly accepted queue and makes it ready only while the originating request still owns it. */
    fun installAndPrepare(
        current: Player,
        item: MediaItem,
        startPositionMs: Long,
        authorizedBeforeInstall: () -> Boolean,
        stillAuthorizedAfterInstall: () -> Boolean,
        startPlaying: Boolean,
    ): Boolean
}

/** Synchronous by design: all callers run on Media3's existing player thread. */
internal class PlaybackCommandEffects(
    private val currentPlayer: () -> Player?,
    private val itemGeneration: () -> Long,
    private val isAttached: (Player) -> Boolean,
    private val protectOutput: (Player, Boolean, Boolean) -> ManualPlayProtection,
) : PlaybackCommands {
    override fun prepare(current: Player, stillAuthorized: () -> Boolean, holdForSeek: Boolean): Boolean {
        val expectedItemGeneration = itemGeneration()
        if (!isCurrent(current, expectedItemGeneration, stillAuthorized)) return false
        if (current.playWhenReady &&
            protectOutput(current, true, holdForSeek) == ManualPlayProtection.Denied
        ) {
            return false
        }
        if (!isCurrent(current, expectedItemGeneration, stillAuthorized) ||
            (holdForSeek && current.playWhenReady)
        ) {
            return false
        }
        current.prepare()
        return isCurrent(current, expectedItemGeneration, stillAuthorized)
    }

    override fun play(current: Player, stillAuthorized: () -> Boolean, beforeRawPlay: () -> Unit): Boolean {
        val expectedItemGeneration = itemGeneration()
        if (!isCurrent(current, expectedItemGeneration, stillAuthorized) ||
            (!current.playWhenReady && protectOutput(current, true, false) == ManualPlayProtection.Denied)
        ) {
            return false
        }
        if (!isCurrent(current, expectedItemGeneration, stillAuthorized)) return false
        beforeRawPlay()
        if (!isCurrent(current, expectedItemGeneration, stillAuthorized)) return false
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
        val beforeInstall = itemGeneration()
        // Installing a paused/armed queue must not inherit a stale playWhenReady from an empty player.
        if (!isCurrent(current, beforeInstall, authorizedBeforeInstall) || shouldRefuseInheritedPlayback(current)) {
            return false
        }
        current.setMediaItem(item, startPositionMs)
        val installedGeneration = itemGeneration()
        val installed = {
            current.currentMediaItem == item && stillAuthorizedAfterInstall()
        }
        if (!isCurrent(current, installedGeneration, installed)) return false
        if (!prepare(current, installed)) return false
        return !startPlaying || play(current, installed)
    }

    private fun isCurrent(current: Player, expectedItemGeneration: Long, stillAuthorized: () -> Boolean): Boolean =
        currentPlayer() === current && isAttached(current) &&
            itemGeneration() == expectedItemGeneration && stillAuthorized()

    private fun shouldRefuseInheritedPlayback(current: Player): Boolean {
        if (!current.playWhenReady) return false
        if (protectOutput(current, false, true) == ManualPlayProtection.Denied) return true
        return current.playWhenReady
    }
}
