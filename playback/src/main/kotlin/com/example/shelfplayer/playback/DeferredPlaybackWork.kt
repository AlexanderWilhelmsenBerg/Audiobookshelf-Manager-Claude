package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * PLAY-001 / ROUTE-002 / issue #257 — deferred service work never wins over newer listener intent.
 *
 * This is not a second playback authority. [PlaybackService] supplies its already-owned player and the
 * existing Media3 controller/profile generations. We also track raw item transitions, which automatic
 * service actions can cause without passing through the controller's forwarding-player invalidation.
 *
 * The acceptance seam is [BookChanges.acceptBook], which checks authorization again after suspending
 * session bookkeeping and on the same main-thread turn as [Player.setMediaItem].
 */
internal class DeferredPlaybackWork(
    private val currentPlayer: () -> Player?,
    private val commandGeneration: () -> Long,
    private val itemGeneration: () -> Long,
    private val profileGeneration: () -> Long,
    private val commands: PlaybackCommands,
    private val acceptPrepared: suspend (CarPostConnectRestorer.Prepared, suspend () -> Boolean, () -> Unit) -> Boolean,
) {
    data class Claim(
        val player: Player,
        val item: MediaItem?,
        val command: Long,
        val itemVersion: Long,
        val profile: Long,
    )

    /** Must be captured on the Media3 player thread, before the asynchronous operation begins. */
    fun capture(): Claim? {
        val active = currentPlayer() ?: return null
        return Claim(
            player = active,
            item = active.currentMediaItem,
            command = commandGeneration(),
            itemVersion = itemGeneration(),
            profile = profileGeneration(),
        )
    }

    /** Both identity and every relevant generation must still belong to the original request. */
    fun isCurrent(claim: Claim): Boolean {
        val active = currentPlayer() ?: return false
        if (active !== claim.player || active.currentMediaItem != claim.item) return false
        return commandGeneration() == claim.command &&
            itemGeneration() == claim.itemVersion &&
            profileGeneration() == claim.profile
    }

    /** Only the exact idle, still-errored stream may be re-prepared after its retry delay. */
    suspend fun retryIfCurrent(claim: Claim, retryIn: Duration) {
        delay(retryIn)
        val active = currentPlayer() ?: return
        if (!isCurrent(claim)) return
        val stillErrored = active.mediaItemCount > 0 &&
            active.playbackState == Player.STATE_IDLE &&
            active.playerError != null
        if (!stillErrored) return
        // Media3 preserves playWhenReady across an error. prepare() restores the old intent without
        // manufacturing a new Play or touching any newer book selected while the delay was suspended.
        commands.prepare(active, stillAuthorized = { isCurrent(claim) })
    }

    /**
     * The candidate has no side effects before acceptance. The durable-session owner must invoke
     * [acceptPrepared]'s authorization predicate immediately before its final player installation.
     */
    suspend fun installIfCurrent(
        claim: Claim,
        prepared: CarPostConnectRestorer.Prepared,
        startPlaying: Boolean,
        condition: (Player) -> Boolean,
        allowed: suspend () -> Boolean = { true },
    ): Boolean {
        val authorized: suspend () -> Boolean = {
            // Profile/lock checks may suspend. Re-read the player and generations afterwards, as the
            // very last synchronous part of the predicate before the session owner installs the item.
            allowed() && isCurrent(claim) && condition(claim.player)
        }
        if (!authorized()) return false
        var effectsApplied = false
        val accepted = acceptPrepared(prepared, authorized) {
            effectsApplied = commands.installAndPrepare(
                current = claim.player,
                item = prepared.queue.item,
                startPositionMs = prepared.queue.startPositionMs,
                authorizedBeforeInstall = { isCurrent(claim) && condition(claim.player) },
                stillAuthorizedAfterInstall = { isInstalledCurrent(claim, prepared.queue.item) },
                startPlaying = startPlaying,
            )
        }
        return accepted && effectsApplied
    }

    private fun isInstalledCurrent(claim: Claim, item: MediaItem): Boolean {
        val active = currentPlayer() ?: return false
        return active === claim.player && active.currentMediaItem == item &&
            commandGeneration() == claim.command && profileGeneration() == claim.profile
    }
}
