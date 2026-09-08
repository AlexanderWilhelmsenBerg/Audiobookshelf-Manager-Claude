package com.example.shelfplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture

/**
 * Issue #91 — the Media3 boundary that makes every standard Play use one freshness decision.
 *
 * The service keeps the wrapped ExoPlayer for its own atomic operations. Only the player exposed through
 * MediaSession is this forwarding player, so app Play, notification/system controls, Bluetooth/headsets and
 * Android Auto all arrive through [handleSetPlayWhenReady].
 *
 * Explicit movement invalidates before forwarding. That ordering is what stops a delayed REST answer from
 * undoing a seek, Stop, book replacement or Pause that happened after the Play request began.
 */
@OptIn(UnstableApi::class)
internal class ResumeFreshnessPlayer(
    private val delegate: Player,
    private val preparePlay: () -> ListenableFuture<*>,
    private val invalidate: (ResumeInvalidation) -> Unit,
) : ForwardingSimpleBasePlayer(delegate) {

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (!playWhenReady) {
            invalidate(ResumeInvalidation.Pause)
            return super.handleSetPlayWhenReady(false)
        }
        // An empty session still needs MediaSession's normal playback-resumption machinery. A duplicate Play
        // while the raw player is already committed to playing also has no paused baseline left to inspect.
        if (delegate.mediaItemCount == 0 || delegate.playWhenReady) {
            return super.handleSetPlayWhenReady(true)
        }
        return preparePlay()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        invalidate(ResumeInvalidation.Seek)
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        invalidate(ResumeInvalidation.MediaChanged)
        return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        invalidate(ResumeInvalidation.MediaChanged)
        return super.handleAddMediaItems(index, mediaItems)
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        invalidate(ResumeInvalidation.MediaChanged)
        return super.handleRemoveMediaItems(fromIndex, toIndex)
    }

    override fun handleReplaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<*> {
        invalidate(ResumeInvalidation.MediaChanged)
        return super.handleReplaceMediaItems(fromIndex, toIndex, mediaItems)
    }

    override fun handleStop(): ListenableFuture<*> {
        invalidate(ResumeInvalidation.Stop)
        return super.handleStop()
    }
}
