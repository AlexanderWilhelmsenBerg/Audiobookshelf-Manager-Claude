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
    private val isCurrentProbe: (Long, ExoPlayer, String) -> Boolean,
    private val onProbeInvalidated: (ExoPlayer) -> Boolean,
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
        detachPlayer()
        lateinit var listener: Player.Listener
        listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (attachedPlayer === target && attachedListener === listener && isPlaying) onPlaying()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (attachedPlayer !== target || attachedListener !== listener) return
                if (!playWhenReady && !isPausingForSafety(target)) cancelProbe()
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

    internal class Probe(val endpointId: String, val player: ExoPlayer, val preferenceEpoch: Long) {
        // The slot is reserved before mute acquisition can invoke synchronous listeners. A null claim exists
        // only during that call; cancellation retires the reservation and its returning owner releases it.
        var muteClaim: PlaybackVolumeEffects.RouteMuteClaim? = null
        var positionCheckpoint: MutedPositionCheckpoint? = null
        var startupTimeout: Job? = null
        var routeTimeout: Job? = null
        var completionJob: Job? = null
        val routeUpdates = Channel<Unit>(Channel.CONFLATED)
        var requiresPlaybackBarrier = false
    }

    /** The service captures before mute listeners can reenter; the Probe remains the sole owner. */
    internal var captureCheckpoint: ((Probe) -> MutedPositionCheckpoint?)? = null
    internal var completeVerifiedProbe: (suspend (Probe) -> MutedProbeCompletion)? = null

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
        active.startupTimeout = scope.launch(playerDispatcher) {
            delay(PROBE_STARTUP_TIMEOUT)
            finishProbe(active, verified = false)
        }
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

    fun hasCurrentProbe(id: String, player: ExoPlayer, preferenceEpoch: Long): Boolean = probe?.let {
        it.muteClaim != null && it.endpointId == id && it.player === player &&
            it.preferenceEpoch == preferenceEpoch && isCurrent(it)
    } == true

    fun verifyProbeIfActive(actualRoute: Set<String>?, collisions: Set<String>, isPlaying: Boolean): Boolean {
        val active = probe ?: return false
        active.routeUpdates.trySend(Unit)
        if (!isCurrent(active)) {
            discardStaleProbe(active)
            return true
        }
        if (active.muteClaim == null || !isPlaying || active.completionJob != null) return true

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
            evidence.confirms(active.endpointId) && active.endpointId !in collisions ->
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
        return true
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
            finishProbeNow(active, verified)
        }
    }

    private fun startCompletion(active: Probe, verified: Boolean) {
        active.routeTimeout?.cancel()
        active.routeTimeout = null
        lateinit var work: Job
        work = scope.launch(playerDispatcher, start = CoroutineStart.LAZY) {
            var completion = MutedProbeCompletion.Refused
            try {
                completion = completeVerifiedProbe?.invoke(active) ?: MutedProbeCompletion.Refused
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: IllegalStateException) {
                // Released/unavailable platform effect: refuse proof and clean up in finally.
            } catch (_: IllegalArgumentException) {
                // A rejected platform seek/message cannot authorize audible playback.
            } catch (_: SecurityException) {
                // Missing observation permission cannot authorize audible playback.
            } catch (_: UnsupportedOperationException) {
                // Unsupported measurement remains unverified.
            } finally {
                if (probe === active && active.completionJob === work) {
                    finishProbeNow(
                        active,
                        verified && completion == MutedProbeCompletion.Verified,
                        safetyPause = completion != MutedProbeCompletion.Superseded,
                    )
                }
            }
        }
        active.completionJob = work
        if (!work.start() && probe === active) finishProbeNow(active, verified = false)
    }

    private fun finishProbeNow(active: Probe, verified: Boolean, safetyPause: Boolean = true): Boolean {
        if (probe !== active || active.muteClaim == null) return false
        if (!isCurrent(active)) {
            discardStaleProbe(active)
            return false
        }
        quiesceProbe(active)
        if (!verified && safetyPause && attachedPlayer === active.player) pauseForSafety(active.player)
        val owned = probe === active
        if (owned) probe = null
        volumeEffects.releaseRouteMute(active.muteClaim)
        if (owned) onProbeFinished(verified)
        return owned
    }

    /** Restore the original player even if a replacement has already become current. */
    fun cancelProbe(expected: Probe? = null) {
        val active = probe ?: return
        if (expected != null && expected !== active) return
        probe = null
        cancelProbeJobs(active)
        volumeEffects.releaseRouteMute(active.muteClaim)
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

    private fun isCurrent(active: Probe): Boolean =
        isCurrentProbe(active.preferenceEpoch, active.player, active.endpointId)

    private fun discardStaleProbe(active: Probe): Boolean {
        if (probe !== active || !onProbeInvalidated(active.player)) return false
        cancelProbe(active)
        return true
    }

    private companion object {
        val MISSING_HEADSET_GRACE = 2.seconds
        val PROBE_STARTUP_TIMEOUT = 4.seconds
        val PROBE_PLAYING_ROUTE_TIMEOUT = 500.milliseconds
    }
}
