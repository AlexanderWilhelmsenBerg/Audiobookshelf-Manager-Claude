package com.example.shelfplayer.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlin.time.Duration

/**
 * BW-SLEEP-02 / PRODUCT_SPEC PLAY-008 — project the playback-owned countdown into MediaSession metadata.
 *
 * Android System UI is host-owned and does not promise to render a custom action's display name or the
 * artist/byline on every compact media surface. Physical acceptance proved that projecting only into those
 * fields leaves BookWave's countdown invisible on a supported phone.
 *
 * While the timer is active, the countdown therefore becomes the complete primary title and display title.
 * Keeping that text to only the short clock avoids a long book-title marquee restarting on every one-second
 * metadata update in compact System UI. The original values travel in BookWave-owned extras so every tick
 * rebuilds from truth, and idle state restores the exact book metadata.
 *
 * The projection is for the shared session (notification, Android Auto, Bluetooth). BookWave's own full
 * player and mini-player read [ordinaryTitle] instead, so the book title never turns into a clock in the app;
 * the app shows the countdown in the Sleep action.
 */
internal object SleepTimerMediaMetadata {

    /** Clock text that stays useful even when System UI gives us only one short line. */
    fun countdownLabel(remaining: Duration): String {
        val millis = remaining.inWholeMilliseconds.coerceAtLeast(0L)
        val totalSeconds = if (millis == 0L) 0L else (millis + MILLIS_PER_SECOND - 1L) / MILLIS_PER_SECOND
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        val totalMinutes = totalSeconds / SECONDS_PER_MINUTE
        if (totalMinutes < MINUTES_PER_HOUR) {
            return totalMinutes.toString() + ":" + seconds.twoDigits()
        }
        val hours = totalMinutes / MINUTES_PER_HOUR
        val minutes = totalMinutes % MINUTES_PER_HOUR
        return hours.toString() + ":" + minutes.twoDigits() + ":" + seconds.twoDigits()
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

        if (
            wasProjected &&
            metadata.title?.toString() == timerLabel &&
            metadata.displayTitle?.toString() == timerLabel
        ) {
            return null
        }

        if (!wasProjected) {
            extras.putBoolean(KEY_PROJECTED, true)
            baseTitle?.let { extras.putCharSequence(KEY_BASE_TITLE, it) }
            baseDisplayTitle?.let { extras.putCharSequence(KEY_BASE_DISPLAY_TITLE, it) }
        }
        val projected = metadata.buildUpon()
            .setTitle(timerLabel)
            .setDisplayTitle(timerLabel)
            .setExtras(extras)
            .build()
        return item.buildUpon().setMediaMetadata(projected).build()
    }

    /**
     * The book's own title, whether or not a countdown is currently projected over it (PD-002: the projection
     * must not change the book's identity inside BookWave).
     */
    fun ordinaryTitle(metadata: MediaMetadata): CharSequence? {
        val extras = metadata.extras
        return if (extras?.getBoolean(KEY_PROJECTED, false) == true) {
            extras.getCharSequence(KEY_BASE_TITLE)
        } else {
            metadata.title
        }
    }

    private fun Long.twoDigits(): String = toString().padStart(2, '0')

    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_HOUR = 60L
    private const val KEY_PROJECTED = "com.example.shelfplayer.sleep_timer_projected"
    private const val KEY_BASE_TITLE = "com.example.shelfplayer.sleep_timer_base_title"
    private const val KEY_BASE_DISPLAY_TITLE = "com.example.shelfplayer.sleep_timer_base_display_title"
}
