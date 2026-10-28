package com.example.shelfplayer.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

    suspend fun complete(probe: HeadsetRouteGuard.Probe): MutedProbeCompletion

    fun position(target: Player): Duration

    fun beforeTransfer(): Boolean

    fun bindTransferScope(probe: HeadsetRouteGuard.Probe, request: OutputIntentOwner.TransferRequest): Boolean

    fun isAuthorized(probe: HeadsetRouteGuard.Probe): Boolean

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
    }

    private var compensation: HeadsetRouteGuard.Probe? = null

    fun install() = outputs.positions.bind(this)

    override fun authority(): Context = source.context()

    override fun position(target: Player): Duration = checkpoint(target)?.position ?: target.bookPosition()

    override fun positionMs(): Long = source.player()?.let(::position)?.inWholeMilliseconds ?: 0L

    override fun isAuthorized(probe: HeadsetRouteGuard.Probe): Boolean =
        probe.positionCheckpoint?.let { authorized(probe, it) } == true

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

    private fun restore(probe: HeadsetRouteGuard.Probe, held: MutedPositionCheckpoint): Boolean {
        if (!authorized(probe, held)) return false
        if (probe.player.bookPosition() == held.position) return true
        val previous = compensation
        probe.requiresPlaybackBarrier = true
        compensation = probe
        try {
            probe.player.seekTo(held.position.inWholeMilliseconds.coerceAtLeast(0L))
        } finally {
            compensation = previous
        }
        return authorized(probe, held)
    }

    override suspend fun complete(probe: HeadsetRouteGuard.Probe): MutedProbeCompletion {
        val held = probe.positionCheckpoint
        var finished = false
        return try {
            completeCurrent(probe).also { finished = true }
        } finally {
            if (!finished && held != null) restore(probe, held)
        }
    }

    private suspend fun completeCurrent(probe: HeadsetRouteGuard.Probe): MutedProbeCompletion {
        val held = probe.positionCheckpoint ?: return MutedProbeCompletion.Refused
        if (!probe.player.playWhenReady) return refusal(probe, held)
        if (!prepareProof(probe, held)) return refusal(probe, held)
        return awaitProof(probe, held)
    }

    private suspend fun prepareProof(probe: HeadsetRouteGuard.Probe, held: MutedPositionCheckpoint): Boolean {
        if (!restore(probe, held)) return false
        if (probe.requiresPlaybackBarrier && !playbackBarrier(probe.player)) return false
        if (!authorized(probe, held)) return false
        probe.requiresPlaybackBarrier = false
        return true
    }

    private suspend fun awaitProof(
        probe: HeadsetRouteGuard.Probe,
        held: MutedPositionCheckpoint,
    ): MutedProbeCompletion = withTimeoutOrNull(1.seconds) {
        while (authorized(probe, held)) {
            compensatedProof(probe, held)?.let { return@withTimeoutOrNull it }
            probe.routeUpdates.receive()
        }
        MutedProbeCompletion.Superseded
    } ?: refusal(probe, held)

    /** The route may become provable only after another unheard interval. Proof before that seek is tentative. */
    private suspend fun compensatedProof(
        probe: HeadsetRouteGuard.Probe,
        held: MutedPositionCheckpoint,
    ): MutedProbeCompletion? {
        val candidate = playingProof(probe, held) ?: return null
        if (!candidate.permitsAudio) return candidate
        return if (!probe.requiresPlaybackBarrier && probe.player.bookPosition() == held.position) {
            candidate
        } else if (prepareProof(probe, held)) {
            playingProof(probe, held)
        } else {
            refusal(probe, held)
        }
    }

    /** Every query is fresh; neither a prior callback nor barrier delivery itself authorizes unmute. */
    private fun playingProof(probe: HeadsetRouteGuard.Probe, held: MutedPositionCheckpoint): MutedProbeCompletion? {
        if (!authorized(probe, held)) return MutedProbeCompletion.Superseded
        val current = probe.player
        if (!current.playWhenReady || !current.isPlaying || current.playbackState != Player.STATE_READY) return null
        return if (probe.transferRequest != null) transferProof(probe, held) else endpointProof(probe, held)
    }

    private fun transferProof(probe: HeadsetRouteGuard.Probe, held: MutedPositionCheckpoint): MutedProbeCompletion? {
        val proof = outputs.handoffs.proof(probe)
        return when {
            !authorized(probe, held) -> MutedProbeCompletion.Superseded
            proof == MutedProbeCompletion.Refused -> refusal(probe, held)
            else -> proof
        }
    }

    private fun endpointProof(probe: HeadsetRouteGuard.Probe, held: MutedPositionCheckpoint): MutedProbeCompletion? {
        val first = outputs.positions.measure()
        if (!authorized(probe, held)) return MutedProbeCompletion.Superseded
        val second = outputs.positions.measure()
        return when {
            !authorized(probe, held) -> MutedProbeCompletion.Superseded

            first == setOf(
                probe.endpointId,
            ) && second == first && outputs.positions.isEligible(probe) -> MutedProbeCompletion.Verified

            !first.isNullOrEmpty() || !second.isNullOrEmpty() -> refusal(probe, held)

            else -> null
        }
    }

    private fun refusal(probe: HeadsetRouteGuard.Probe, held: MutedPositionCheckpoint): MutedProbeCompletion {
        if (!restore(probe, held)) return MutedProbeCompletion.Superseded
        if (probe.transferRequest != null) outputs.handoffs.rollback(probe)
        return if (authorized(probe, held)) MutedProbeCompletion.Refused else MutedProbeCompletion.Superseded
    }

    private suspend fun playbackBarrier(current: ExoPlayer): Boolean = withTimeoutOrNull(1.seconds) {
        suspendCancellableCoroutine { continuation ->
            val message = current.createMessage(
                PlayerMessage.Target { _, _ ->
                    if (continuation.isActive) continuation.resume(Unit)
                },
            ).send()
            continuation.invokeOnCancellation { message.cancel() }
        }
    } != null
}
