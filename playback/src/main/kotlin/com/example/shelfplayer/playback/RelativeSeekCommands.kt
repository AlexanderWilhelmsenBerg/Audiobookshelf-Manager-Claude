package com.example.shelfplayer.playback

import androidx.media3.common.Player
import com.example.shelfplayer.core.model.playback.SkipIntervals
import kotlin.time.Duration

/**
 * Issue #197 / PLAY-007 — which seek commands mean a relative jump by the configured skip interval.
 *
 * A book is one Media3 timeline window (ADR-0016), so Previous/Next can only mean a relative jump. Left
 * unmapped, `seekToPrevious()` past 3 s seeks to 0 and restarted a ~30-hour book from a headset button.
 *
 * Next is normally unavailable for a single item, so it stays a no-op unless a controller advertises it.
 * Intervals are read by the caller at press time, so a changed setting applies to the very next press.
 */
internal object RelativeSeekCommands {
    fun deltaFor(seekCommand: Int, skips: SkipIntervals): Duration? = when (seekCommand) {
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_BACK,
        -> -skips.back

        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_FORWARD,
        -> skips.forward

        else -> null
    }
}
