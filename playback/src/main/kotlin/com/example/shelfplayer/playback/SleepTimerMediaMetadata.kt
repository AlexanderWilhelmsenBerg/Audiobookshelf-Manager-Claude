package com.example.shelfplayer.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import kotlin.time.Duration

/**
 * BW-SLEEP-02 / PRODUCT_SPEC PLAY-008 — project the playback-owned countdown into MediaSession metadata.
 *
 * Android System UI is host-owned and does not promise to render a custom action's display name or the
 * artist/byline on every compact media surface. Physical acceptance proved that projecting only into those
 * fields leaves BookWave's countdown invisible on a supported phone.
 *
 * The countdown is therefore projected at the start of the primary title and display title while active.
 * Those are the metadata fields the compact and expanded system media surfaces are built around. The original
 * values travel in BookWave-owned extras so every one-second tick rebuilds from truth rather than compounding
 * prefixes, and idle state restores the exact book metadata.
 */
internal object SleepTimerMediaMetadata {

    /** Clock text that stays useful even when System UI gives us only one short line. */
    fun countdownLabel(remaining: Duration): String {
        val millis = remaining.inWholeMilliseconds.coerceAtLeast(0L)
        val totalSeconds = if (millis == 0L) 0L else (millis + MILLIS_PER_SECOND - 1L) / MILLIS_PER_SECOND
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        val totalMinutes = totalSeconds / SECONDS_PER_MINUTE
        if (totalMinutes < MINUTES_PER_HOUR) {
            return "$" + "{totalMinutes}:" + "$" + "{seconds.twoDigits()}"
        }
        val hours = totalMinutes / MINUTES_PER_HOUR
        val minutes = totalMinutes % MINUTES_PER_HOUR
        return "$" + "{hours}:" + "$" + "{minutes.twoDigits()}:" + "$" + "{seconds.twoDigits()}"
    }

    /** Returns a replacement item only when the public media metadata actually needs to change. */
    fun project(item: MediaItem, timerLabel: String?): MediaItem? {
        val metadata = item.mediaMetadata
        val extras = Bundle(metadata.extras ?: Bundle.EMPTY)
        val wasProjected = extras.getBoolean(KEY_PROJECTED, false)
        val baseTitle = if (wasProjected) extras.getCharSequence(KEY_BASE_TITLE) else metadata.title
        val baseDisplayTitle =
            if (wasProjected) extras.getCharSequence(KEY_BASE_DISPLAY_TITLE) else metadata.displayTitle

        if (timerLabel == null) {
            if (!wasProjected) return null
            extras.remove(KEY_PROJECTED)
            extras.remove(KEY_BASE_TITLE)
            extras.remove(KEY_BASE_DISPLAY_TITLE)
            val restored = metadata.buildUpon()
                .setTitle(baseTitle)
                .setDisplayTitle(baseDisplayTitle)
                .setExtras(extras)
                .build()
            return item.buildUpon().setMediaMetadata(restored).build()
        }

        val visibleBookTitle = baseDisplayTitle?.takeIf { it.isNotBlank() } ?: baseTitle?.takeIf { it.isNotBlank() }
        val projectedTitle = listOfNotNull(timerLabel, visibleBookTitle).joinToString(SEPARATOR)
        if (
            wasProjected &&
            metadata.title?.toString() == projectedTitle &&
            metadata.displayTitle?.toString() == projectedTitle
        ) {
            return null
        }

        if (!wasProjected) {
            extras.putBoolean(KEY_PROJECTED, true)
            baseTitle?.let { extras.putCharSequence(KEY_BASE_TITLE, it) }
            baseDisplayTitle?.let { extras.putCharSequence(KEY_BASE_DISPLAY_TITLE, it) }
        }
        val projected = metadata.buildUpon()
            .setTitle(projectedTitle)
            .setDisplayTitle(projectedTitle)
            .setExtras(extras)
            .build()
        return item.buildUpon().setMediaMetadata(projected).build()
    }

    private fun Long.twoDigits(): String = toString().padStart(2, '0')

    private const val SEPARATOR = " · "
    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_HOUR = 60L
    private const val KEY_PROJECTED = "com.example.shelfplayer.sleep_timer_projected"
    private const val KEY_BASE_TITLE = "com.example.shelfplayer.sleep_timer_base_title"
    private const val KEY_BASE_DISPLAY_TITLE = "com.example.shelfplayer.sleep_timer_base_display_title"
}
