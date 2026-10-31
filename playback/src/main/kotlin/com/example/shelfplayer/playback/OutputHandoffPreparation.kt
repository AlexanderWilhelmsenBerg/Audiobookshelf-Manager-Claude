package com.example.shelfplayer.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** No state owner: preparation stays inside the existing probe's checkpoint and cancellation scope. */
@androidx.annotation.OptIn(UnstableApi::class)
internal object OutputHandoffPreparation {
    fun startPosition(probe: HeadsetRouteGuard.Probe, held: Duration): Duration {
        if (probe.transferRequest == null || probe.player.bookPosition() <= held) return held
        // Decode/attach before the held narration instead of consuming it while the replacement becomes READY.
        // Scale media time by speed; this is preparation runway, never additional permitted unheard progress.
        val runway = PREPARATION_TIME * probe.player.playbackParameters.speed.toDouble()
        return (held - runway).coerceAtLeast(Duration.ZERO)
    }

    suspend fun awaitHeldPosition(player: ExoPlayer, start: Duration, held: Duration): Boolean {
        val position = player.bookPosition()
        if (position < start) return false
        return position >= held || awaitMessage(player, held.inWholeMilliseconds)
    }

    suspend fun awaitMessage(player: ExoPlayer, positionMs: Long? = null): Boolean = withTimeoutOrNull(1.seconds) {
        suspendCancellableCoroutine { continuation ->
            val message = player.createMessage(
                PlayerMessage.Target { _, _ ->
                    // Delivery runs on Media3's playback thread. Only resume; all player/route reads stay on main.
                    if (continuation.isActive) continuation.resume(Unit)
                },
            ).apply { if (positionMs != null) setPosition(positionMs) }.send()
            continuation.invokeOnCancellation { message.cancel() }
        }
    } != null

    private val PREPARATION_TIME = 250.milliseconds
}
