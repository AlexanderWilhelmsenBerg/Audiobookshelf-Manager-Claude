package com.example.shelfplayer.core.model.playback

import java.time.Instant
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-003 — why a position changed discontinuously.
 *
 * Only jumps are recorded. Ordinary playback is a line, and writing it down would be writing down a clock.
 * These five are the moments where a listener's position moved without them listening to the gap, which is
 * exactly the set they might want to reverse.
 */
enum class PlaybackJump {
    /** A drag on either seek bar, or a tap on the notification's. */
    Seek,

    /** A skip button, in either direction. */
    Skip,

    /** Chapter navigation, from the transport or the chapter list. */
    Chapter,

    /** PRODUCT_SPEC PLAY-009 — the app moved the position, not the listener. */
    AutoRewind,

    /** Where a session opened. The one entry with no "from", because there was no before. */
    Resume,
    ;

    companion object {
        /** PRODUCT_SPEC SYNC-001 — an unrecognized stored value reads back as the commonest kind. */
        fun parse(name: String?): PlaybackJump = entries.firstOrNull { it.name == name } ?: Seek
    }
}

/**
 * One jump, both ends of it.
 *
 * @property from where the listener was, or `null` for [PlaybackJump.Resume].
 * @property to where they ended up. Tapping the entry goes back to [from] — the point is the undo.
 */
data class PlaybackHistoryEntry(
    val id: String,
    val jump: PlaybackJump,
    val from: Duration?,
    val to: Duration,
    val at: Instant,
)
