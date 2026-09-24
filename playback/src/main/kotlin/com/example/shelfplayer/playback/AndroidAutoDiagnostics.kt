package com.example.shelfplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.shelfplayer.core.common.log.LogField

/**
 * Issues #36/#88 — privacy-safe facts for correlating Android Auto with Media3.
 *
 * Titles and URI values are deliberately absent. [LogField.Identifier] hashes opaque ids through the
 * application redactor, while URI handling records only the scheme/class of source Android Auto was handed.
 */
@OptIn(UnstableApi::class)
internal object AndroidAutoDiagnostics {

    /**
     * Snapshot of the live Player. Call only from the player's application thread.
     */
    fun playerFields(player: Player?): List<LogField> {
        if (player == null) {
            return listOf(LogField.Public("player", "absent"))
        }
        return buildList {
            add(LogField.Public("player", "present"))
            add(LogField.Count("itemCount", player.mediaItemCount))
            add(LogField.Public("itemIndex", player.currentMediaItemIndex))
            add(LogField.Public("state", playerStateName(player.playbackState)))
            add(LogField.Public("playWhenReady", player.playWhenReady))
            add(LogField.Public("isPlaying", player.isPlaying))
            add(LogField.Millis("position", player.currentPosition.coerceAtLeast(0L)))
            addAll(itemFields(player.currentMediaItem, prefix = "current"))
        }
    }

    /** One stable, redacted fingerprint for a Media3 item. */
    fun itemFields(item: MediaItem?, prefix: String = "item"): List<LogField> {
        if (item == null) return listOf(LogField.Public(prefix, "none"))
        val metadata = item.mediaMetadata
        val owner = MediaItems.ownerOf(item)
        return buildList {
            add(LogField.Public("${prefix}Kind", itemKind(item)))
            add(LogField.Identifier("${prefix}Id", item.mediaId))
            add(LogField.Public("${prefix}Playable", metadata.isPlayable?.toString() ?: "unknown"))
            add(LogField.Public("${prefix}Browsable", metadata.isBrowsable?.toString() ?: "unknown"))
            add(LogField.Public("${prefix}Placeholder", MediaItems.isResumePlaceholder(item)))
            add(LogField.Public("${prefix}HasUri", item.localConfiguration != null))
            add(LogField.Public("${prefix}UriClass", uriClass(item)))
            add(LogField.Public("${prefix}HasArtwork", metadata.artworkUri != null))
            owner?.let { add(LogField.Identifier("${prefix}Owner", it.value)) }
        }
    }

    fun itemKind(item: MediaItem): String = when {
        MediaItems.isResumePlaceholder(item) -> "holder"
        MediaItems.isReadyToPlay(item) -> "playable-book"
        else -> AutoLibrary.kindOf(item.mediaId)
    }

    fun uriClass(item: MediaItem): String {
        if (MediaItems.isResumePlaceholder(item)) return "inert-placeholder"
        val scheme = item.localConfiguration?.uri?.scheme?.lowercase() ?: return "none"
        return when (scheme) {
            "http", "https" -> "http"
            "content" -> "content"
            "file" -> "file"
            "data" -> "data"
            else -> "other"
        }
    }

    fun playerStateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> "unknown"
    }

    fun mediaTransitionReason(reason: Int): String = when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto"
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek"
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "playlistChanged"
        else -> "unknown"
    }
}
