package com.example.shelfplayer.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * PLAY-002 / ROUTE-002 — owns the temporary headset-continuity lifetime: its exact endpoint, bounded disappearance
 * observation, and any muted verification tied to one attached player. Endpoint discovery and
 * listener selection remain in [AudioOutputRouter].
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class HeadsetRouteGuard(
    private val scope: CoroutineScope,
    private val playerDispatcher: CoroutineDispatcher,
    private val volumeEffects: PlaybackVolumeEffects,
    private val isCurrentScope: (Probe) -> Boolean,
    private val onProbeInvalidated: (Probe) -> Boolean,
    private val onProbeFinished: (Boolean) -> Unit,
    private val onPlaying: () -> Unit,
) {
    private var attachedPlayer: ExoPlayer? = null
    private var attachedListener: Player.Listener? = null
    private var safetyPausePlayer: ExoPlayer? = null

    /** Local effect provenance, never a claim about the physical controller that sent a command. */
    fun isPausingForSafety(target: ExoPlayer?): Boolean = target != null && safetyPausePlayer === target

    fun pauseForSafety(target: ExoPlayer): Boolean {
        if (attachedPlayer !== target) return false
        val previous = safetyPausePlayer
        safetyPausePlayer = target
        try {
            target.pause()
        } finally {
            safetyPausePlayer = previous
        }
        return true
    }

    fun attachPlayer(target: ExoPlayer) {
        cancelProbe()
        detachPlayer()
        lateinit var listener: Player.Listener
        listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (attachedPlayer === target && attachedListener === listener && isPlaying) onPlaying()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (attachedPlayer !== target || attachedListener !== listener) return
                if (!playWhenReady && !isPausingForSafety(target)) retirePausedProbe(target)
            }
        }
        attachedPlayer = target
        attachedListener = listener
        target.addListener(listener)
    }

    fun detachPlayer() {
        val previousPlayer = attachedPlayer
        val previousListener = attachedListener
        attachedListener = null
        attachedPlayer = null
        if (previousListener != null) previousPlayer?.removeListener(previousListener)
    }

    val currentPlayerListener: Player.Listener? get() = attachedListener

    val preservablePlayer: ExoPlayer?
        get() = attachedPlayer?.takeIf { probe == null && !volumeEffects.isRouteMuted(it) }

    internal class Probe(
        val endpointId: String?,
        val player: ExoPlayer,
        val preferenceEpoch: Long,
        val transferRequest: OutputIntentOwner.TransferRequest? = null,
    ) {
        // The slot is reserved before mute acquisition can invoke synchronous listeners. A null claim exists
        // only during that call; cancellation retires the reservation and its returning owner releases it.
        var muteClaim: PlaybackVolumeEffects.RouteMuteClaim? = null
        var positionCheckpoint: MutedPositionCheckpoint? = null
        var startupTimeout: Job? = null
        var routeTimeout: Job? = null
        var completionJob: Job? = null
        val routeUpdates = Channel<Unit>(Channel.CONFLATED)
        var requiresPlaybackBarrier = false

        fun matches(id: String, expectedPlayer: ExoPlayer, epoch: Long): Boolean {
            val sameAttachment = player === expectedPlayer && preferenceEpoch == epoch
            return muteClaim != null && endpointId == id && sameAttachment
        }
    }

    /** The service captures before mute listeners can reenter; the Probe remains the sole owner. */
    internal var captureCheckpoint: ((Probe) -> MutedPositionCheckpoint?)? = null
    internal var completeVerifiedProbe: (suspend (Probe, Boolean) -> MutedProbeCompletion)? = null
    internal var commitVerifiedProbe: ((Probe, MutedProbeCompletion) -> MutedProbeCompletion)? = null
    internal var onProbeCancelled: ((Probe) -> Unit)? = null
    internal var onProbeSettled: ((Probe, MutedProbeCompletion) -> Unit)? = null
    internal var onExternalPause: ((Probe) -> Unit)? = null

    private fun retirePausedProbe(target: ExoPlayer) {
        val active = probe?.takeIf { it.player === target } ?: return
        quiesceProbe(active)
        try {
            onExternalPause?.invoke(active)
        } finally {
            if (probe === active) {
                if (target.playWhenReady) {
                    armStartupTimeout(active)
                    onPlaying()
                } else {
                    cancelProbe(active)
                }
            }
        }
    }

    private fun armStartupTimeout(active: Probe) {
        active.startupTimeout?.cancel()
        active.startupTimeout = scope.launch(playerDispatcher) {
            delay(PROBE_STARTUP_TIMEOUT)
            finishProbe(active, verified = false)
        }
    }

    var endpointId: String? = null
        private set

    var endpointMissing: Boolean = false
        private set

    var disappearanceGrace: Job? = null
        private set

    private var probe: Probe? = null

    fun protect(id: String) {
        cancelGrace()
        endpointId = id
        endpointMissing = false
    }

    fun markMissing(): Boolean {
        val alreadyMissing = endpointMissing
        endpointMissing = true
        return alreadyMissing
    }

    fun startGrace(block: suspend () -> Unit): Boolean {
        if (disappearanceGrace != null) return false
        disappearanceGrace = scope.launch {
            val owner = currentCoroutineContext().job
            try {
                delay(MISSING_HEADSET_GRACE)
                block()
            } finally {
                if (disappearanceGrace === owner) disappearanceGrace = null
            }
        }
        return true
    }

    fun endpointReturned() {
        cancelGrace()
        endpointMissing = false
    }

    fun release() {
        clearOwnership()
        cancelProbe()
    }

    fun clearOwnership() {
        cancelGrace()
        endpointId = null
        endpointMissing = false
    }

    fun beginProbe(id: String, player: ExoPlayer, preferenceEpoch: Long): Boolean {
        if (probe != null || attachedPlayer !== player) return false
        val active = Probe(id, player, preferenceEpoch)
        probe = active
        if (!captureReservation(active)) {
            if (probe === active) probe = null
            return false
        }
        val claim = volumeEffects.acquireRouteMute(player)
        active.muteClaim = claim
        if (claim == null || !ownsReservation(active)) {
            if (probe === active) cancelProbe(active) else volumeEffects.releaseRouteMute(claim)
            return false
        }
        armStartupTimeout(active)
        return true
    }

    private fun ownsReservation(active: Probe): Boolean =
        probe === active && attachedPlayer === active.player && isCurrent(active)

    private fun captureReservation(active: Probe): Boolean {
        val capture = captureCheckpoint ?: return true
        val checkpoint = capture(active)
        if (!ownsReservation(active)) return false
        active.positionCheckpoint = checkpoint
        return checkpoint != null
    }

    /** Reserve and mute before publishing intent. Detached predecessor claims remain held until this returns. */
    fun beginTransfer(
        request: OutputIntentOwner.TransferRequest,
        player: ExoPlayer,
        publish: (Probe) -> Boolean,
    ): Boolean {
        if (attachedPlayer !== player) return false
        val previous = probe
        previous?.let(::quiesceProbe)
        val active = Probe(request.selection.outputId, player, request.effectEpoch, request)
        active.requiresPlaybackBarrier = previous?.requiresPlaybackBarrier == true
        probe = active
        return try {
            acquireTransfer(active, previous?.positionCheckpoint, publish)
        } finally {
            previous?.let(::releaseDetachedProbe)
        }
    }

    private fun acquireTransfer(
        active: Probe,
        fallback: MutedPositionCheckpoint?,
        publish: (Probe) -> Boolean,
    ): Boolean {
        var claim: PlaybackVolumeEffects.RouteMuteClaim? = null
        var accepted = false
        try {
            if (!captureReservation(active)) return false
            claim = volumeEffects.acquireRouteMute(active.player)
            if (claim == null || !ownsReservation(active) || !publish(active)) return false
            if (!ownsReservation(active)) return false
            active.muteClaim = claim
            armStartupTimeout(active)
            accepted = true
            return true
        } finally {
            if (!accepted) discardFailedTransfer(active, fallback, claim)
        }
    }

    private fun discardFailedTransfer(
        active: Probe,
        fallback: MutedPositionCheckpoint?,
        claim: PlaybackVolumeEffects.RouteMuteClaim?,
    ) {
        if (probe === active) {
            if (active.positionCheckpoint == null) active.positionCheckpoint = fallback
            onProbeInvalidated(active)
            if (probe === active) probe = null
        }
        cancelProbeJobs(active)
        volumeEffects.releaseRouteMute(claim)
    }

    private fun releaseDetachedProbe(active: Probe) {
        cancelProbeJobs(active)
        volumeEffects.releaseRouteMute(active.muteClaim)
        onProbeCancelled?.invoke(active)
    }

    fun verifyProbeIfActive(actualRoute: Set<String>?, collisions: Set<String>, isPlaying: Boolean): Boolean {
        val active = probe ?: return false
        active.routeUpdates.trySend(Unit)
        if (!isCurrent(active)) {
            discardStaleProbe(active)
            return true
        }
        if (active.muteClaim == null || !isPlaying || active.completionJob != null) return true
        if (active.transferRequest != null) {
            finishProbe(active, verified = true)
        } else {
            verifyEndpoint(active, actualRoute, collisions)
        }
        return true
    }

    private fun verifyEndpoint(active: Probe, actualRoute: Set<String>?, collisions: Set<String>) {
        val evidence = RouteEvidence(
            source = RouteEvidenceSource.ActualAudioTrack,
            confidence = when {
                actualRoute.isNullOrEmpty() -> RouteConfidence.Unknown
                actualRoute.size > 1 -> RouteConfidence.Ambiguous
                else -> RouteConfidence.Confirmed
            },
            endpointId = actualRoute?.singleOrNull(),
        )
        when {
            active.endpointId != null && evidence.confirms(active.endpointId) && active.endpointId !in collisions ->
                finishProbe(active, verified = true)

            !actualRoute.isNullOrEmpty() ->
                finishProbe(active, verified = false)

            active.routeTimeout == null -> {
                active.routeTimeout = scope.launch(playerDispatcher) {
                    delay(PROBE_PLAYING_ROUTE_TIMEOUT)
                    finishProbe(active, verified = false)
                }
            }
        }
    }

    /** Finish only the currently-owned probe; repeated callbacks for a retired probe are no-ops. */
    fun finishProbe(active: Probe, verified: Boolean): Boolean {
        if (probe !== active || active.muteClaim == null) return false
        if (!isCurrent(active)) {
            discardStaleProbe(active)
            return false
        }
        return if (active.positionCheckpoint != null && completeVerifiedProbe != null) {
            if (active.completionJob == null) startCompletion(active, verified)
            true
        } else {
            finishProbeNow(active, if (verified) MutedProbeCompletion.Verified else MutedProbeCompletion.Refused)
        }
    }

    private fun startCompletion(active: Probe, verified: Boolean) {
        active.routeTimeout?.cancel()
        active.routeTimeout = null
        lateinit var work: Job
        work = scope.launch(playerDispatcher, start = CoroutineStart.LAZY) {
            var completion = MutedProbeCompletion.Refused
            try {
                completion = completeOrRefuse(active, verified)
            } finally {
                if (probe === active && active.completionJob === work) {
                    finishProbeNow(
                        active,
                        if (verified || completion == MutedProbeCompletion.Superseded) {
                            completion
                        } else {
                            MutedProbeCompletion.Refused
                        },
                    )
                }
            }
        }
        active.completionJob = work
        if (!work.start() && probe === active) finishProbeNow(active, MutedProbeCompletion.Refused)
    }

    private suspend fun completeOrRefuse(active: Probe, verified: Boolean): MutedProbeCompletion = try {
        completeVerifiedProbe?.invoke(active, verified) ?: MutedProbeCompletion.Refused
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (_: IllegalStateException) {
        MutedProbeCompletion.Refused
    } catch (_: IllegalArgumentException) {
        MutedProbeCompletion.Refused
    } catch (_: SecurityException) {
        MutedProbeCompletion.Refused
    } catch (_: UnsupportedOperationException) {
        MutedProbeCompletion.Refused
    }

    private fun finishProbeNow(active: Probe, completion: MutedProbeCompletion): Boolean {
        if (probe !== active || active.muteClaim == null) return false
        if (!isCurrent(active)) {
            discardStaleProbe(active)
            return false
        }
        val held = active.positionCheckpoint
        val work = active.completionJob
        var result = MutedProbeCompletion.Refused
        var finished = false
        try {
            result = if (completion.permitsAudio) {
                commitVerifiedProbe?.invoke(active, completion)
                    ?: if (active.transferRequest == null) completion else MutedProbeCompletion.Refused
            } else {
                completion
            }
        } finally {
            if (canFinalize(active, held, work)) finished = releaseFinishedProbe(active, result)
        }
        return finished
    }

    private fun releaseFinishedProbe(active: Probe, completion: MutedProbeCompletion): Boolean {
        quiesceProbe(active)
        val owned: Boolean
        try {
            val needsPause = !completion.permitsAudio && completion != MutedProbeCompletion.Superseded
            if (needsPause && attachedPlayer === active.player && active.player.playWhenReady) {
                pauseForSafety(active.player)
            }
        } finally {
            owned = probe === active
            if (owned) probe = null
            volumeEffects.releaseRouteMute(active.muteClaim)
            if (owned) {
                onProbeSettled?.invoke(active, completion)
                onProbeFinished(completion.permitsAudio)
            }
        }
        return owned
    }

    /** Commit callbacks may synchronously replace the probe or rebase the same probe's held position. */
    private fun canFinalize(active: Probe, held: MutedPositionCheckpoint?, work: Job?): Boolean {
        if (probe !== active) {
            volumeEffects.releaseRouteMute(active.muteClaim)
            return false
        }
        if (active.positionCheckpoint !== held || active.completionJob !== work) return false
        if (!isCurrent(active)) {
            discardStaleProbe(active)
            return false
        }
        return true
    }

    /** Restore the original player even if a replacement has already become current. */
    fun cancelProbe(expected: Probe? = null) {
        val active = probe ?: return
        if (expected != null && expected !== active) return
        probe = null
        cancelProbeJobs(active)
        volumeEffects.releaseRouteMute(active.muteClaim)
        onProbeCancelled?.invoke(active)
    }

    fun cancelProbeForEndpoint(id: String, preferenceEpoch: Long) {
        probe?.takeIf { it.endpointId == id && it.preferenceEpoch == preferenceEpoch }?.let(::cancelProbe)
    }

    val positionCheckpoint: MutedPositionCheckpoint?
        get() = currentProbe?.takeIf { volumeEffects.isRouteMuted(it.player) }?.positionCheckpoint

    val currentProbe: Probe? get() = probe?.takeIf { attachedPlayer === it.player }

    fun quiesceProbe(active: Probe) {
        if (probe !== active) return
        val work = active.completionJob
        active.completionJob = null
        work?.cancel()
        active.startupTimeout?.cancel()
        active.routeTimeout?.cancel()
    }

    fun isCurrentProbe(active: Probe): Boolean = probe === active && isCurrent(active)

    fun replacePositionCheckpoint(
        target: ExoPlayer,
        expected: MutedPositionCheckpoint,
        replacement: MutedPositionCheckpoint,
    ): Boolean {
        val active = probe?.takeIf {
            it.player === target && volumeEffects.isRouteMuted(target) && isCurrent(it)
        } ?: return false
        if (active.positionCheckpoint !== expected) return false
        active.positionCheckpoint = replacement
        active.requiresPlaybackBarrier = true
        val work = active.completionJob
        active.completionJob = null
        work?.cancel()
        active.routeTimeout?.cancel()
        active.routeTimeout = null
        onPlaying()
        return true
    }

    private fun cancelGrace() {
        disappearanceGrace?.cancel()
        disappearanceGrace = null
    }

    private fun cancelProbeJobs(active: Probe) {
        active.startupTimeout?.cancel()
        active.routeTimeout?.cancel()
        active.completionJob?.cancel()
    }

    private fun isCurrent(active: Probe): Boolean = isCurrentScope(active)

    private fun discardStaleProbe(active: Probe): Boolean {
        if (probe !== active || !onProbeInvalidated(active)) return false
        cancelProbe(active)
        return true
    }

    private companion object {
        val MISSING_HEADSET_GRACE = 2.seconds
        val PROBE_STARTUP_TIMEOUT = 4.seconds
        val PROBE_PLAYING_ROUTE_TIMEOUT = 500.milliseconds
    }
}
