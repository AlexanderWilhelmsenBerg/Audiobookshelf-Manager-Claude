package com.example.shelfplayer.playback

import android.os.Bundle
import androidx.media3.common.MediaItem

/**
 * BW-SLEEP-02 / PRODUCT_SPEC PLAY-008 — project the playback-owned countdown into MediaSession metadata.
 *
 * Android 13+ builds its media card from session metadata rather than an app-owned notification layout.
 * The active timer therefore joins the existing artist/byline while it is running. The original artist is
 * carried in BookWave-owned extras so each one-second timer tick can rebuild from truth instead of appending
 * another "Sleep …" fragment, and so idle state restores the exact byline.
 */
internal object SleepTimerMediaMetadata {

    /** Returns a replacement item only when the public media metadata actually needs to change. */
    fun project(item: MediaItem, timerLabel: String?): MediaItem? {
        val metadata = item.mediaMetadata
        val extras = Bundle(metadata.extras ?: Bundle.EMPTY)
        val wasProjected = extras.getBoolean(KEY_PROJECTED, false)
        val baseArtist = if (wasProjected) {
            extras.getString(KEY_BASE_ARTIST)
        } else {
            metadata.artist?.toString()
        }

        if (timerLabel == null) {
            if (!wasProjected) return null
            extras.remove(KEY_PROJECTED)
            extras.remove(KEY_BASE_ARTIST)
            val restored = metadata.buildUpon()
                .setArtist(baseArtist)
                .setExtras(extras)
                .build()
            return item.buildUpon().setMediaMetadata(restored).build()
        }

        val projectedArtist = listOfNotNull(
            baseArtist?.takeIf(String::isNotBlank),
            timerLabel,
        ).joinToString(SEPARATOR)
        if (wasProjected && metadata.artist?.toString() == projectedArtist) return null

        if (!wasProjected) {
            extras.putBoolean(KEY_PROJECTED, true)
            baseArtist?.let { extras.putString(KEY_BASE_ARTIST, it) }
        }
        val projected = metadata.buildUpon()
            .setArtist(projectedArtist)
            .setExtras(extras)
            .build()
        return item.buildUpon().setMediaMetadata(projected).build()
    }

    private const val SEPARATOR = " · "
    private const val KEY_PROJECTED = "com.example.shelfplayer.sleep_timer_projected"
    private const val KEY_BASE_ARTIST = "com.example.shelfplayer.sleep_timer_base_artist"
}
