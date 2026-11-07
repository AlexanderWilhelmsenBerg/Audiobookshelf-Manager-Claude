package com.example.shelfplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.example.shelfplayer.core.common.log.Logger
import kotlinx.coroutines.delay

/** Observation only; the existing probe owns the job, deadline, checkpoint and permission to unmute. */
@OptIn(UnstableApi::class)
internal object MutedRouteChecks {
    /** Runs on the probe's player dispatcher; never queues a callback into a successor probe. */
    fun observe(
        logger: Logger,
        guard: HeadsetRouteGuard,
        probe: HeadsetRouteGuard.Probe,
        ordinal: Int,
        routes: () -> Set<String>?,
        collisions: Set<String>,
    ) {
        MutedRouteDiagnostics.observation(logger, probe, ordinal)
        if (!authorized(guard, probe)) return
        val actual = routes()
        val playing = probe.player.isPlaying
        if (authorized(guard, probe)) guard.verifyProbeIfActive(actual, collisions, playing)
    }

    private fun authorized(guard: HeadsetRouteGuard, probe: HeadsetRouteGuard.Probe): Boolean =
        guard.isCurrentProbe(probe) && probe.canCommit() && guard.canObserveProbe(probe) &&
            guard.isCurrentProbe(probe) && probe.player.playWhenReady

    suspend fun run(isCurrent: () -> Boolean, observe: (Int) -> Unit) {
        // Five reads at approximately 20/50/100/200/300ms. Scheduling never extends the probe deadline.
        DELAYS.forEachIndexed { index, wait ->
            delay(wait)
            if (!isCurrent()) return
            observe(index + 1)
        }
    }

    private val DELAYS = listOf(20L, 30L, 50L, 100L, 100L)
}
