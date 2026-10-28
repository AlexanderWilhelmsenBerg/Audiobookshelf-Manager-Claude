package com.example.shelfplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.shelfplayer.core.model.playback.SkipIntervals
import com.google.common.util.concurrent.Futures
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
 * [handleSeek] also maps relative/transport seeks (Previous, Next, SeekBack, SeekForward) to the configured
 * skip interval (#197, see [RelativeSeekCommands]).
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
    private val preparePlay: (bypassFreshness: Boolean) -> ListenableFuture<*>,
    private val consumeFreshStart: () -> Boolean,
    private val invalidate: (ResumeInvalidation) -> Boolean,
    private val onPlayWhenReadyRequest: (Boolean) -> Boolean,
    private val skipIntervals: () -> SkipIntervals,
    private val prepareCurrentOutput: () -> ListenableFuture<*>,
    private val movementEffects: PlaybackMovementEffects,
    private val allowEffects: () -> Boolean = { true },
) : ForwardingSimpleBasePlayer(delegate) {

    public override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        movement(if (playWhenReady) PlaybackMovement.Play else PlaybackMovement.Pause) {
            // This is the controller intent boundary, not an observed ExoPlayer state transition. In particular,
            // a second Pause while focus has already made playWhenReady=false still reaches here, which lets #36
            // invalidate stale car-continuity evidence even though the delegate may emit no listener callback.
            if (!onPlayWhenReadyRequest(playWhenReady) || (!playWhenReady && !invalidate(ResumeInvalidation.Pause))) {
                return@movement Futures.immediateFuture(false)
            }
            if (!playWhenReady) {
                return@movement super.handleSetPlayWhenReady(false)
            }
            // An empty session still needs MediaSession's normal playback-resumption machinery. A duplicate Play
            // keeps its already-committed raw intent and does not consume a pending fresh-start token.
            if (delegate.mediaItemCount == 0) {
                return@movement super.handleSetPlayWhenReady(true)
            }
            val bypassFreshness = delegate.playWhenReady || consumeFreshStart()
            preparePlay(bypassFreshness)
        }

    public override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (!allowEffects()) return Futures.immediateVoidFuture()
        val target = relativeTarget(seekCommand)
        return movement(PlaybackMovement.Seek) {
            if (!invalidate(ResumeInvalidation.Seek)) return@movement Futures.immediateFuture(false)
            if (target == null) {
                super.handleSeek(mediaItemIndex, positionMs, seekCommand)
            } else {
                super.handleSeek(delegate.currentMediaItemIndex, target, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            }
        }
    }

    /** #197 — a relative command becomes an absolute position in the single book window; null if not relative. */
    private fun relativeTarget(seekCommand: Int): Long? {
        if (delegate.mediaItemCount == 0) return null
        val delta = RelativeSeekCommands.deltaFor(seekCommand, skipIntervals()) ?: return null
        return (movementEffects.positionMs() + delta.inWholeMilliseconds).coerceAtLeast(0L)
    }

    public override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        if (!allowEffects()) return Futures.immediateVoidFuture()
        return movement(PlaybackMovement.MediaChanged) {
            if (!invalidate(ResumeInvalidation.MediaChanged)) return@movement Futures.immediateFuture(false)
            super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
        }
    }

    public override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        if (!allowEffects()) return Futures.immediateVoidFuture()
        return movement(PlaybackMovement.MediaChanged) {
            if (!invalidate(ResumeInvalidation.MediaChanged)) return@movement Futures.immediateFuture(false)
            super.handleAddMediaItems(index, mediaItems)
        }
    }

    public override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        if (!allowEffects()) return Futures.immediateVoidFuture()
        return movement(PlaybackMovement.MediaChanged) {
            if (!invalidate(ResumeInvalidation.MediaChanged)) return@movement Futures.immediateFuture(false)
            super.handleRemoveMediaItems(fromIndex, toIndex)
        }
    }

    public override fun handleReplaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<*> {
        if (!allowEffects()) return Futures.immediateVoidFuture()
        return movement(PlaybackMovement.MediaChanged) {
            if (!invalidate(ResumeInvalidation.MediaChanged)) return@movement Futures.immediateFuture(false)
            super.handleReplaceMediaItems(fromIndex, toIndex, mediaItems)
        }
    }

    public override fun handleStop(): ListenableFuture<*> {
        if (!allowEffects()) return Futures.immediateVoidFuture()
        return movement(PlaybackMovement.Stop) {
            if (!invalidate(ResumeInvalidation.Stop)) return@movement Futures.immediateFuture(false)
            super.handleStop()
        }
    }

    /** Media3 also prepares/seeks before its fallback Play on a failed cold-resumption future. */
    public override fun handlePrepare(): ListenableFuture<*> {
        if (!allowEffects()) return Futures.immediateVoidFuture()
        return prepareCurrentOutput()
    }

    private inline fun movement(command: PlaybackMovement, effect: () -> ListenableFuture<*>): ListenableFuture<*> {
        if (!allowEffects()) return Futures.immediateVoidFuture()
        val finished = movementEffects.before(command) ?: return Futures.immediateFuture(false)
        return try {
            effect()
        } finally {
            finished()
        }
    }
}
