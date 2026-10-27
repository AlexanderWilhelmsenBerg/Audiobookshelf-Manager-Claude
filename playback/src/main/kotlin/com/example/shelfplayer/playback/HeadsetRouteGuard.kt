package com.example.shelfplayer.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private val isCurrentProbe: (Long, ExoPlayer, String) -> Boolean,
    private val onProbeInvalidated: (ExoPlayer) -> Boolean,
    private val onProbeFinished: (Boolean) -> Unit,
    private val onPlaying: () -> Unit,
) {
    private var attachedPlayer: ExoPlayer? = null
    private var attachedListener: Player.Listener? = null

    fun attachPlayer(target: ExoPlayer) {
        detachPlayer()
        lateinit var listener: Player.Listener
        listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (attachedPlayer === target && attachedListener === listener && isPlaying) onPlaying()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (attachedPlayer === target && attachedListener === listener && !playWhenReady) cancelProbe()
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

    fun currentPlayerListener(): Player.Listener? = attachedListener

    internal class Probe(
        val endpointId: String,
        val player: ExoPlayer,
        val previousVolume: Float,
        val preferenceEpoch: Long,
    ) {
        var startupTimeout: Job? = null
        var routeTimeout: Job? = null
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
        if (probe != null) return false
        val active = Probe(id, player, player.volume, preferenceEpoch)
        player.volume = 0f
        probe = active
        active.startupTimeout = scope.launch(playerDispatcher) {
            delay(PROBE_STARTUP_TIMEOUT)
            finishProbe(active, verified = false)
        }
        return true
    }

    fun verifyProbeIfActive(actualRoute: Set<String>?, collisions: Set<String>, isPlaying: Boolean): Boolean {
        val active = probe ?: return false
        if (!isCurrent(active)) {
            discardStaleProbe(active)
            return true
        }
        if (!isPlaying) return true

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
        if (probe !== active) return false
        if (!isCurrent(active)) {
            discardStaleProbe(active)
            return false
        }
        probe = null
        cancelProbeJobs(active)
        if (!verified && attachedPlayer === active.player) active.player.pause()
        active.player.volume = active.previousVolume
        onProbeFinished(verified)
        return true
    }

    /** Restore the original player even if a replacement has already become current. */
    fun cancelProbe(expected: Probe? = null) {
        val active = probe ?: return
        if (expected != null && expected !== active) return
        probe = null
        cancelProbeJobs(active)
        active.player.volume = active.previousVolume
    }

    private fun cancelGrace() {
        disappearanceGrace?.cancel()
        disappearanceGrace = null
    }

    private fun cancelProbeJobs(active: Probe) {
        active.startupTimeout?.cancel()
        active.routeTimeout?.cancel()
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
