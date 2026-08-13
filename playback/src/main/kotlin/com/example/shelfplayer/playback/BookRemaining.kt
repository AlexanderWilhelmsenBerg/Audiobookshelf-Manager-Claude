package com.example.shelfplayer.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * PRODUCT_SPEC PLAY-001 — how much of the **book** is left, for the notification.
 *
 * ### Why the notification needs this at all
 *
 * Media3 renders the progress bar and the two clocks from the player's current media item. This app's
 * playlist is one item per audio *file* (PLAY-003 — see `MediaItems`), so those numbers describe the file,
 * not the book. On a library where each file is a chapter that reads as "time left in this chapter", which
 * is useful but is not the number a listener plans an evening around.
 *
 * The book's own remaining time is not derivable from the player's timeline for the same reason. It comes
 * from the same two facts every other global position in the app comes from: the current track's start
 * offset and the book's duration, both carried in each item's extras.
 *
 * ### Attached, like the timer and the rewind
 *
 * A `@Singleton` given the player by the service, so the provider can read it without holding a reference to
 * anything that outlives a session.
 */
@Singleton
class BookRemaining @Inject constructor() {

    private var player: Player? = null

    fun attach(player: Player?) {
        this.player = player
    }

    /**
     * What is left of the book, or `null` when there is nothing to say.
     *
     * `null` rather than zero for an unknown duration: a book still being prepared has no duration yet, and
     * "0 min left" on a book that has not started is worse than saying nothing. Main thread only, as every
     * [Player] read must be.
     */
    fun remaining(): Duration? {
        val media = player ?: return null
        return remainingOf(media.currentMediaItem, media.currentPosition)
    }

    /**
     * The arithmetic, separated from the player read so it can be tested.
     *
     * `SimpleBasePlayer` seals `getCurrentMediaItem` and `getCurrentPosition`, so a fake player would have to
     * be a whole `State` with its own playlist — a second copy of the book, built by the test, which is where
     * a fake starts asserting its own arithmetic instead of the code's. The two-property read above is the
     * same one three other classes in this module do.
     */
    internal fun remainingOf(item: MediaItem?, positionMs: Long): Duration? {
        if (item == null) return null
        val duration = MediaItems.bookDurationOf(item)
        if (duration <= Duration.ZERO) return null
        val position = MediaItems.globalPositionOf(item, positionMs)
        return (duration - position).coerceAtLeast(Duration.ZERO)
    }

    /**
     * The remaining time as the notification shows it, or `null` when there is nothing to say.
     *
     * Rounded **down** to the minute, deliberately. A label that rounds up says "3 h 25 min left" for a book
     * with 3 h 24 m 01 s to go and then appears not to move for fifty-nine seconds; rounding down means the
     * number always changes when the minute does. Under a minute is a phrase rather than "0 min left".
     */
    fun label(context: Context): String? = labelFor(context, remaining())

    internal fun labelFor(context: Context, remaining: Duration?): String? {
        if (remaining == null) return null
        if (remaining < 1.minutes) return context.getString(R.string.player_book_remaining_moments)
        val hours = remaining.inWholeHours.toInt()
        val minutes = (remaining.inWholeMinutes % MINUTES_PER_HOUR).toInt()
        return if (hours > 0) {
            context.getString(R.string.player_book_remaining_hours, hours, minutes)
        } else {
            context.getString(R.string.player_book_remaining_minutes, minutes)
        }
    }

    private companion object {
        const val MINUTES_PER_HOUR = 60L
    }
}
