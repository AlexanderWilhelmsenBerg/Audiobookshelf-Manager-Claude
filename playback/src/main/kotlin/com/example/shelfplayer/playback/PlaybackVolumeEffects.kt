package com.example.shelfplayer.playback

import androidx.media3.common.Player
import com.example.shelfplayer.core.common.time.AppClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/** One dispatcher-confined writer for the shared Media3 player's volume effects. */
@Singleton
class PlaybackVolumeEffects @Inject constructor(private val clock: AppClock) {
    internal class RouteMuteClaim internal constructor(internal val player: Player)

    private class Attachment(val player: Player, var requestedGain: Float)

    internal data class MuteChange(val player: Player, val muted: Boolean, val elapsed: Duration)

    private var current: Attachment? = null
    private val claimOwners = linkedMapOf<RouteMuteClaim, Attachment>()
    private val listeners = linkedSetOf<(MuteChange) -> Unit>()

    /** Idempotent for the current player; attaching never rewrites its volume. */
    internal fun attach(target: Player) {
        if (current?.player === target) return
        current = Attachment(target, target.volume)
    }

    /** Retire the exact attachment without touching a player that may already be released. */
    internal fun detach(expected: Player? = null) {
        val active = current
        if (expected != null && active?.player !== expected) {
            // Retire only that predecessor's claims; a delayed old detach must not unbind its successor.
            claimOwners.entries.removeAll { it.key.player === expected }
            return
        }
        if (active == null) return
        claimOwners.entries.removeAll { it.value === active }
        current = null
    }

    /** Store the latest sleep gain even while a route proof owns silence. */
    internal fun setSleepGain(target: Player, gain: Float) {
        val active = current?.takeIf { it.player === target } ?: return
        val requested = gain.coerceIn(0f, 1f)
        if (active.requestedGain == requested) return
        active.requestedGain = requested
        writeEffectiveGain(active)
    }

    internal fun acquireRouteMute(target: Player): RouteMuteClaim? {
        val active = current?.takeIf { it.player === target } ?: return null
        val wasMuted = activeHasClaims(active)
        val claim = RouteMuteClaim(target)
        claimOwners[claim] = active
        if (!wasMuted) {
            target.volume = 0f
            notifyMute(target, true)
        }
        return claim
    }

    internal fun releaseRouteMute(claim: RouteMuteClaim?): Boolean {
        if (claim == null) return false
        val attachment = claimOwners.remove(claim) ?: return false
        if (!activeHasClaims(attachment)) {
            if (current === attachment) {
                writeEffectiveGain(attachment)
                notifyMute(attachment.player, false)
            } else {
                // A successor is current; retire the old probe against its own player only.
                attachment.player.volume = attachment.requestedGain
            }
        }
        return true
    }

    internal fun isRouteMuted(target: Player?): Boolean =
        target != null && current?.let { it.player === target && activeHasClaims(it) } == true

    internal fun addMuteListener(listener: (MuteChange) -> Unit) {
        listeners += listener
    }

    internal fun removeMuteListener(listener: (MuteChange) -> Unit) {
        listeners -= listener
    }

    private fun activeHasClaims(attachment: Attachment): Boolean = claimOwners.values.any { it === attachment }

    private fun writeEffectiveGain(attachment: Attachment) {
        if (current === attachment) {
            attachment.player.volume = if (activeHasClaims(attachment)) 0f else attachment.requestedGain
        }
    }

    private fun notifyMute(target: Player, muted: Boolean) {
        val change = MuteChange(target, muted, clock.elapsed())
        listeners.toList().forEach { listener ->
            if (listener in listeners) listener(change)
        }
    }
}
