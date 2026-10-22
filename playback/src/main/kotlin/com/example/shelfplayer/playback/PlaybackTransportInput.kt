package com.example.shelfplayer.playback

import androidx.media3.common.Player

/**
 * Interprets raw Media3 transport changes without becoming a playback-state or destination owner.
 *
 * Media3 local-player USER_REQUEST is not physical-input attribution: app, notification, wheel,
 * headset and system transports can all converge on the same Play. REMOTE is mapped as user intent
 * for a future remote-player adapter but cannot identify the speaker or a headset button.
 */
internal object PlaybackTransportInput {
    data class Interpretation(
        val isPlay: Boolean,
        val isUserPause: Boolean,
        val interruptionReason: Int?,
        val reasonName: String,
    )

    fun interpret(playWhenReady: Boolean, reason: Int): Interpretation {
        val isUserPause = !playWhenReady && (
            reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ||
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
        )
        val platformInterruption = if (!playWhenReady && (
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
                )
        ) {
            reason
        } else {
            null
        }
        return Interpretation(
            isPlay = playWhenReady,
            isUserPause = isUserPause,
            interruptionReason = platformInterruption,
            reasonName = reasonName(reason),
        )
    }

    private fun reasonName(reason: Int): String = when (reason) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "userRequest"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audioFocusLoss"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "becomingNoisy"
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "endOfItem"
        Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "suppressedTooLong"
        else -> "unknown"
    }
}
