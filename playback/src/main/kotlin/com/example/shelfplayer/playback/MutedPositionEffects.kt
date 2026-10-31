package com.example.shelfplayer.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal enum class PlaybackMovement { Play, Pause, Stop, Seek, MediaChanged, ReplaceCurrent }

/** Transport adapters expose this boundary; the Probe remains the sole checkpoint owner. */
internal interface PlaybackMovementEffects {
    /** Null refuses an operation superseded inside synchronous effects. */
    fun before(command: PlaybackMovement): (() -> Unit)?

    fun positionMs(): Long
}

internal interface PositionCheckpointEffects {
    fun authority(): MutedPositionEffects.Context

    fun capture(probe: HeadsetRouteGuard.Probe): MutedPositionCheckpoint?

    suspend fun restoreForProof(probe: HeadsetRouteGuard.Probe): Boolean

    fun restoreCurrent(probe: HeadsetRouteGuard.Probe): Boolean

    fun position(target: Player): Duration

    fun beforeTransfer(): Boolean

    fun bindTransferScope(probe: HeadsetRouteGuard.Probe, request: OutputIntentOwner.TransferRequest): Boolean

    fun isAuthorized(probe: HeadsetRouteGuard.Probe): Boolean

    fun isSettled(probe: HeadsetRouteGuard.Probe): Boolean

    fun restorePausedProbe(probe: HeadsetRouteGuard.Probe)
}

/** Transient position compensation; repository progress and session ownership stay with their existing owners. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class MutedPositionEffects(private val outputs: AudioOutputRouter, private val source: Source) :
    PlaybackMovementEffects,
    PositionCheckpointEffects {
    internal data class Context(val command: Long, val item: Long, val profile: Long)

    internal interface Source {
        fun player(): ExoPlayer?

        fun context(): Context

        fun onSettlingRefused(advance: Duration) = Unit

        fun onCompensated(advance: Duration) = Unit

        fun onPrepared(probe: HeadsetRouteGuard.Probe, bufferedSeek: Boolean) = Unit
    }

    private var compensation: HeadsetRouteGuard.Probe? = null

    fun install() = outputs.positions.bind(this)

    override fun authority(): Context = source.context()

    override fun position(target: Player): Duration = checkpoint(target)?.position ?: target.bookPosition()

    override fun positionMs(): Long = source.player()?.let(::position)?.inWholeMilliseconds ?: 0L

    override fun isAuthorized(probe: HeadsetRouteGuard.Probe): Boolean =
        probe.positionCheckpoint?.let { authorized(probe, it) } == true

    override fun isSettled(probe: HeadsetRouteGuard.Probe): Boolean {
        val held = probe.positionCheckpoint ?: return false
        val advance = probe.player.bookPosition() - held.position
        if (!authorized(probe, held)) return false
        if (!probe.requiresPlaybackBarrier && advance in Duration.ZERO..MAX_SETTLING_ADVANCE) return true
        source.onSettlingRefused(advance)
        return false
    }

    override fun restorePausedProbe(probe: HeadsetRouteGuard.Probe) {
        if (!probe.player.playWhenReady) probe.positionCheckpoint?.let { restore(probe, it) }
    }

    /** Preserve active Play intent while moving its still-muted checkpoint into a replacement reservation. */
    override fun beforeTransfer(): Boolean {
        val current = source.player() ?: return false
        val stamp = source.context()
        val selection = outputs.positions.intent.selection
        val active = outputs.positions.guard.currentProbe ?: return unchanged(current, stamp, selection)
        outputs.positions.guard.quiesceProbe(active)
        val held = active.positionCheckpoint ?: return false
        return restore(active, held) && unchanged(current, stamp, selection)
    }

    override fun bindTransferScope(
        probe: HeadsetRouteGuard.Probe,
        request: OutputIntentOwner.TransferRequest,
    ): Boolean {
        val held = probe.positionCheckpoint ?: return false
        val stamp = Context(held.controllerCommandGeneration, held.mediaItemGeneration, held.profileGeneration)
        val samePlayback = ownsMedia(probe.player, held) && source.context() == stamp
        val sameRequest = outputs.positions.intent.selection == request.selection &&
            request.bookGeneration == held.bookGeneration
        if (!samePlayback || !sameRequest) return false
        val bound = held.copy(selectionSequence = request.selection.sequence)
        probe.positionCheckpoint = bound
        return authorized(probe, bound)
    }

    override fun capture(probe: HeadsetRouteGuard.Probe): MutedPositionCheckpoint? {
        val stamp = source.context()
        val current = source.player()?.takeIf { it === probe.player } ?: return null
        val item = current.currentMediaItem ?: return null
        if (MediaItems.isResumePlaceholder(item)) return null
        val intent = outputs.outputIntentOwner.state.value
        val captured = MutedPositionCheckpoint(
            item = item,
            position = current.bookPosition(),
            controllerCommandGeneration = stamp.command,
            mediaItemGeneration = stamp.item,
            profileGeneration = stamp.profile,
            bookGeneration = intent.activeBookGeneration,
            selectionSequence = intent.selection?.sequence,
        )
        return captured.takeIf { authorized(probe, it) }
    }

    private fun ownsMedia(current: Player, held: MutedPositionCheckpoint): Boolean {
        val stamp = source.context()
        return source.player() === current && current.currentMediaItem === held.item &&
            stamp.item == held.mediaItemGeneration && stamp.profile == held.profileGeneration &&
            outputs.isAttachedPlayer(current)
    }

    private fun checkpoint(current: Player): MutedPositionCheckpoint? {
        if (source.player() !== current) return null
        if (outputs.positions.guard.currentProbe?.player !== current) return null
        val held = outputs.positions.guard.positionCheckpoint ?: return null
        return held.takeIf { ownsMedia(current, it) }
    }

    private fun authorized(probe: HeadsetRouteGuard.Probe, held: MutedPositionCheckpoint): Boolean {
        val intent = outputs.outputIntentOwner.state.value
        return probe.positionCheckpoint.let { it == null || it === held } &&
            outputs.positions.guard.isCurrentProbe(probe) && ownsMedia(probe.player, held) &&
            source.context().command == held.controllerCommandGeneration &&
            intent.activeBookGeneration == held.bookGeneration && intent.selection?.sequence == held.selectionSequence
    }

    /** Restore before command invalidation, but release silence only after a real Pause/Stop. */
    override fun before(command: PlaybackMovement): (() -> Unit)? {
        val stamp = source.context()
        val current = source.player() ?: return {}
        val selection = outputs.positions.intent.selection
        val probe = outputs.positions.guard.currentProbe?.takeIf { it.player === current }
            ?: return beforeCurrentReplacement(current, command, stamp, selection)
        outputs.positions.guard.quiesceProbe(probe)
        val restored = restoreBeforeMovement(probe, command)
        if (!restored || !unchanged(current, stamp, selection)) {
            outputs.positions.guard.cancelProbe(probe)
            return null
        }
        return if (command == PlaybackMovement.Pause || command == PlaybackMovement.Stop) {
            { outputs.positions.guard.cancelProbe(probe) }
        } else {
            // A new Play/seek/install cannot inherit an old unverified activation or its timeout.
            if (outputs.positions.guard.currentProbe === probe) outputs.positions.guard.pauseForSafety(current)
            outputs.positions.guard.cancelProbe(probe)
            if (unchanged(current, stamp, selection)) ({}) else null
        }
    }

    /** Media3 may prepare a replacement internally without a later controller Prepare or Play. */
    private fun beforeCurrentReplacement(
        current: ExoPlayer,
        command: PlaybackMovement,
        stamp: Context,
        selection: OutputIntentOwner.Selection?,
    ): (() -> Unit)? {
        val guarded = outputs.positions.guard.endpointId != null || outputs.positions.intent.lock != null ||
            outputs.positions.intent.transfer != null
        val needsHold = command == PlaybackMovement.ReplaceCurrent && guarded
        if (needsHold && current.playWhenReady && outputs.protectManualPlayOnGuardedHeadset(
                current,
                willStartAudio = false,
                holdPaused = true,
            ) == ManualPlayProtection.Denied
        ) {
            return null
        }
        val held = !needsHold || !current.playWhenReady
        return if (held && unchanged(current, stamp, selection)) ({}) else null
    }

    private fun unchanged(current: ExoPlayer, stamp: Context, selection: OutputIntentOwner.Selection?): Boolean {
        val sameAttachment = source.player() === current && outputs.isAttachedPlayer(current)
        return sameAttachment && source.context() == stamp && outputs.positions.intent.selection == selection
    }

    private fun restoreBeforeMovement(probe: HeadsetRouteGuard.Probe, command: PlaybackMovement): Boolean {
        val held = probe.positionCheckpoint ?: return true
        return command == PlaybackMovement.Seek || !authorized(probe, held) || restore(probe, held)
    }

    /** A genuine adopted seek or auto-rewind intentionally changes the held point. */
    fun onSeek(current: ExoPlayer): Boolean {
        val correcting = compensation
        val held = correcting?.positionCheckpoint
        if (correcting?.player === current && held != null && source.context() == Context(
                held.controllerCommandGeneration,
                held.mediaItemGeneration,
                held.profileGeneration,
            )
        ) {
            return true
        }
        val old = checkpoint(current) ?: return false
        if (outputs.positions.guard.replacePositionCheckpoint(
                current,
                old,
                old.copy(position = current.bookPosition()),
            )
        ) {
            outputs.positions.guard.currentProbe?.let { outputs.handoffs.rebased(it) }
        }
        return false
    }

    fun retireForItemTransition() {
        val current = source.player() ?: return
        val probe = outputs.positions.guard.currentProbe?.takeIf { it.player === current } ?: return
        outputs.positions.guard.quiesceProbe(probe)
        outputs.positions.guard.pauseForSafety(probe.player)
        outputs.positions.guard.cancelProbe(probe)
    }

    private fun restore(
        probe: HeadsetRouteGuard.Probe,
        held: MutedPositionCheckpoint,
        target: Duration = held.position,
    ): Boolean {
        if (!authorized(probe, held)) return false
        val advance = probe.player.bookPosition() - target
        if (advance == Duration.ZERO) return true
        val previous = compensation
        probe.requiresPlaybackBarrier = true
        compensation = probe
        try {
            probe.player.seekTo(target.inWholeMilliseconds.coerceAtLeast(0L))
        } finally {
            compensation = previous
        }
        source.onCompensated(advance)
        return authorized(probe, held)
    }

    override fun restoreCurrent(probe: HeadsetRouteGuard.Probe): Boolean =
        probe.positionCheckpoint?.let { restore(probe, it) } == true

    override suspend fun restoreForProof(probe: HeadsetRouteGuard.Probe): Boolean {
        val held = probe.positionCheckpoint?.takeIf { authorized(probe, it) } ?: return false
        val current = probe.player
        val start = OutputHandoffPreparation.startPosition(probe, held.position)
        val seekingFromReady = current.bookPosition() != held.position && current.playbackState == Player.STATE_READY
        var bufferedSeek = false
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (seekingFromReady && playbackState == Player.STATE_BUFFERING && authorized(probe, held)) {
                    bufferedSeek = true
                }
            }
        }
        current.addListener(listener)
        try {
            if (!restore(probe, held, start)) return false
            bufferedSeek = bufferedSeek || (seekingFromReady && current.playbackState == Player.STATE_BUFFERING)
            val acknowledged = !probe.requiresPlaybackBarrier || OutputHandoffPreparation.awaitMessage(current)
            if (!acknowledged || !authorized(probe, held)) return false
            probe.requiresPlaybackBarrier = false
            source.onPrepared(probe, bufferedSeek)
            return authorized(probe, held) &&
                (start == held.position || OutputHandoffPreparation.awaitHeldPosition(current, start, held.position)) &&
                authorized(probe, held)
        } finally {
            current.removeListener(listener)
        }
    }

    private companion object {
        // A bound on unheard media, not a wall-clock or route-mismatch tolerance. Target proof stays exact.
        val MAX_SETTLING_ADVANCE = 50.milliseconds
    }
}
