package com.example.shelfplayer.playback

import androidx.media3.common.Player
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.time.AppClock
import kotlin.time.Duration

/**
 * PLAY-002 / section 14.5: passive Media3 evidence, never a focus or recovery owner.
 *
 * Media3's public events collapse permanent loss and request denial, and speech ducking with transient
 * loss. They expose neither the raw Android callback nor its requester. Suppression clearance is not a
 * raw focus-gain callback, and isPlaying is not proof of audible output during a muted route probe.
 * One bounded diagnostic interval belongs to this listener/player; nothing here authorizes Play.
 */
internal class PlaybackFocusDiagnostics(private val clock: AppClock, private val emit: (List<LogField>) -> Unit) :
    Player.Listener {
    private var transportReason: Int? = null
    private var beganAt: Duration? = null
    private var sequence = 0L

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        transportReason = reason
    }

    override fun onEvents(player: Player, events: Player.Events) {
        val reason = transportReason
        transportReason = null
        val loss = events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) &&
            reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS && !player.playWhenReady
        val transient = events.contains(Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED) &&
            player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        if (beganAt == null && !loss && !transient) return
        val observation = observation(player, events, loss, transient) ?: return
        val now = clock.elapsed()
        if (beganAt == null) {
            beganAt = now
            sequence++
        }
        emit(
            listOf(
                LogField.Public("focusObservation", observation),
                LogField.Public("focusEvidence", "media3-derived"),
                LogField.Public("androidFocusChange", "unavailable"),
                LogField.Public("focusRequester", "unknown"),
                LogField.Public("focusRequesterEvidence", "not-exposed"),
                LogField.Public("focusSequence", sequence),
                LogField.Millis("elapsedRealtime", now.inWholeMilliseconds),
                LogField.Millis(
                    "sinceFocusEvent",
                    (now - requireNotNull(beganAt)).inWholeMilliseconds.coerceAtLeast(0),
                ),
                LogField.Public("transportReasonCode", reason?.toString() ?: "none"),
                LogField.Public("suppressionReasonCode", player.playbackSuppressionReason),
                LogField.Public("playWhenReady", player.playWhenReady),
                LogField.Public("isPlaying", player.isPlaying),
                LogField.Public("audibility", "not-measured"),
            ),
        )
        if (observation in TERMINAL_OBSERVATIONS) beganAt = null
    }

    private fun observation(player: Player, events: Player.Events, loss: Boolean, transient: Boolean): String? = when {
        loss -> "loss-or-denial"

        transient -> "transient-suppression"

        events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) -> "media-transition"

        events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && !player.playWhenReady -> "transport-paused"

        events.contains(Player.EVENT_IS_PLAYING_CHANGED) && player.isPlaying -> "playback-active"

        events.contains(Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED) &&
            player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE -> "suppression-cleared"

        events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) -> "transport-play"

        events.contains(
            Player.EVENT_PLAYBACK_STATE_CHANGED,
        ) && player.playbackState == Player.STATE_IDLE -> "player-idle"

        else -> null
    }

    private companion object {
        val TERMINAL_OBSERVATIONS = setOf("transport-paused", "playback-active", "media-transition", "player-idle")
    }
}
