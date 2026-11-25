package com.example.shelfplayer.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlin.time.Duration.Companion.seconds

/** Optional car-lab policy. The production guard owns the mute, position, deadlines and final output proof. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class BookHeadsetComparison(
    private val guard: HeadsetRouteGuard,
    private val outputs: AndroidLocalOutputAdapter,
    private val owner: OutputIntentOwner,
    private val preferences: RoutePreferenceEffects,
    private val currentGuard: (Long, ExoPlayer, String) -> Boolean,
    private val refresh: () -> Unit,
) {
    var carSession: () -> Boolean = { false }
    private var run: BookHeadsetHoldRun? = null

    fun select(next: BookHeadsetHoldRun?) {
        if (next === run) return
        run = next
        guard.currentProbe?.takeIf { it.returnedToInventory != null }?.let {
            guard.quiesceProbe(it)
            guard.finishProbe(it, verified = false)
        }
        if (next != null) refresh()
    }

    fun observe(player: ExoPlayer, id: String, measured: Set<String>?, playing: Boolean) {
        val active = run ?: return
        val onLiveTarget = measured == setOf(id) && id in outputs.endpointIds
        if (playing && onLiveTarget && identified(id)) {
            active.measured(player, id, owner.state.value)
        }
    }

    fun holding(id: String): Boolean =
        guard.currentProbe?.let { it.endpointId == id && it.returnedToInventory != null } == true

    fun onEndpointReturn(id: String, player: ExoPlayer): Boolean {
        if (!holding(id)) return false
        if (identified(id)) outputs.requestPreference(player, id)
        return true
    }

    fun hold(id: String, player: ExoPlayer?): Boolean {
        val active = run ?: return false
        return when {
            holding(id) -> true
            player == null || !player.isPlaying || !carSession() -> false
            else -> start(active, id, player)
        }
    }

    private fun start(active: BookHeadsetHoldRun, id: String, player: ExoPlayer): Boolean {
        val epoch = preferences.snapshot()
        if (!currentGuard(epoch, player, id) || !identified(id) || !active.consume(player, id, owner.state.value)) {
            return false
        }
        val started = guard.beginProbe(
            id,
            player,
            epoch,
            remaining = 2.seconds,
            canCommit = { run === active && identified(id) },
            returnedToInventory = { id in outputs.endpointIds },
        )
        active.record(if (started) "hold-started" else "hold-unavailable")
        return started
    }

    fun settled(proof: MutedProbeCompletion) {
        run?.record?.invoke("hold-" + proof.name)
    }

    private fun identified(id: String): Boolean = id !in outputs.collidingOutputIds && outputs.retainsIdentity(id)
}
