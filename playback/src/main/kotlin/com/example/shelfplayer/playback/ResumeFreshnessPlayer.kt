package com.example.shelfplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.shelfplayer.core.model.playback.SkipIntervals
import com.google.common.util.concurrent.ListenableFuture

/**
 * Issue #91 — the Media3 boundary that makes every standard Play use one freshness decision.
 *
 * The service keeps the wrapped ExoPlayer for its own atomic operations. Only the player exposed through
 * MediaSession is this forwarding player, so app Play, notification/system controls, Bluetooth/headsets and
 * Android Auto all arrive through [handleSetPlayWhenReady].
 *
 * A server `/play` that was opened specifically for an immediate start already chose the authoritative
 * position. [consumeFreshStart] lets exactly that first Play pass through without asking the server the same
 * question again. Arm-only sessions never receive such a token, so their later Play enters [preparePlay].
 *
 * Explicit movement invalidates before forwarding. That ordering is what stops a delayed REST answer from
 * undoing a seek, Stop, book replacement or Pause that happened after the Play request began.
 *
 * [onPlayWhenReadyRequest] exposes standard Play/Pause intent before delegate state changes. #36 uses that
 * boundary because a duplicate Pause while focus already has the player paused may produce no listener
 * callback, yet it is still newer listener intent and must cancel automatic continuity.
 */
@OptIn(UnstableApi::class)
internal class ResumeFreshnessPlayer(
    private val delegate: Player,
    private val preparePlay: () -> ListenableFuture<*>,
    private val consumeFreshStart: () -> Boolean,
    private val invalidate: (ResumeInvalidation) -> Unit,
    private val onPlayWhenReadyRequest: (Boolean) -> Unit,
    private val skipIntervals: () -> SkipIntervals,
) : ForwardingSimpleBasePlayer(delegate) {

    public override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        // This is the controller intent boundary, not an observed ExoPlayer state transition. In particular,
        // a second Pause while focus has already made playWhenReady=false still reaches here, which lets #36
        // invalidate stale car-continuity evidence even though the delegate may emit no listener callback.
        onPlayWhenReadyRequest(playWhenReady)
        if (!playWhenReady) {
            invalidate(ResumeInvalidation.Pause)
            return super.handleSetPlayWhenReady(false)
        }
        // An empty session still needs MediaSession's normal playback-resumption machinery. A duplicate Play
        // while the raw player is already committed to playing also has no paused baseline left to inspect.
        // Neither consumes a fresh-start token: there is no loaded first Play in either case.
        if (delegate.mediaItemCount == 0 || delegate.playWhenReady) {
            return super.handleSetPlayWhenReady(true)
        }
        if (consumeFreshStart()) return super.handleSetPlayWhenReady(true)
        return preparePlay()
    }

    public override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        invalidate(ResumeInvalidation.Seek)
        val target = relativeTarget(seekCommand)
            ?: return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        return super.handleSeek(delegate.currentMediaItemIndex, target, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    }

    /** #197 — a relative command becomes an absolute position in the single book window; null if not relative. */
    private fun relativeTarget(seekCommand: Int): Long? {
        if (delegate.mediaItemCount == 0) return null
        val delta = RelativeSeekCommands.deltaFor(seekCommand, skipIntervals()) ?: return null
        return (delegate.currentPosition + delta.inWholeMilliseconds).coerceAtLeast(0L)
    }

    public override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        invalidate(ResumeInvalidation.MediaChanged)
        return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
    }

    public override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        invalidate(ResumeInvalidation.MediaChanged)
        return super.handleAddMediaItems(index, mediaItems)
    }

    public override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        invalidate(ResumeInvalidation.MediaChanged)
        return super.handleRemoveMediaItems(fromIndex, toIndex)
    }

    public override fun handleReplaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<*> {
        invalidate(ResumeInvalidation.MediaChanged)
        return super.handleReplaceMediaItems(fromIndex, toIndex, mediaItems)
    }

    public override fun handleStop(): ListenableFuture<*> {
        invalidate(ResumeInvalidation.Stop)
        return super.handleStop()
    }
}
