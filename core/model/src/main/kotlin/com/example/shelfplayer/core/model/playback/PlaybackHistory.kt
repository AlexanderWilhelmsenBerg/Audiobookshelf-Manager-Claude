package com.example.shelfplayer.core.model.playback

import java.time.Instant
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-003 / PLAY-008 — something that happened to this book, at a position.
 *
 * ### Why this is not only jumps any more
 *
 * The first version recorded five kinds of position change and nothing else, on the reasoning that
 * ordinary playback is a line and writing it down would be writing down a clock. A device run disagreed:
 * *"Play start and play pause doesn't show. And starting sleep timer doesn't show."*
 *
 * The reasoning was right about the clock and wrong about what a history is for. A listener does not open
 * it to audit position arithmetic — they open it to answer "what happened, and can I get back to before
 * it". "I paused here" and "I set a timer here" are answers to that; a per-second position log is not.
 *
 * So: **discontinuities and decisions.** Not the passage of time.
 */
enum class PlaybackEvent {
    /** A drag on either seek bar, or a tap on the notification's. */
    Seek,

    /** A skip button, in either direction. */
    Skip,

    /** Chapter navigation, from the transport or the chapter list. */
    Chapter,

    /** PRODUCT_SPEC PLAY-009 — the app moved the position after a pause, not the listener. */
    AutoRewind,

    /** Where a session opened. */
    Resume,

    /** Playback started or resumed. Recorded from `playWhenReady`, not from `isPlaying` — see below. */
    Play,

    /** Playback paused, whether the listener asked or audio focus took it away. */
    Pause,

    /** PRODUCT_SPEC PLAY-008 — a timer was set. [PlaybackHistoryEntry.detail] carries its length. */
    SleepTimerStarted,

    /** The notification action or a shake extended it. [PlaybackHistoryEntry.detail] is the new remainder. */
    SleepTimerExtended,

    /** The timer ran out and paused the book. */
    SleepTimerExpired,

    /**
     * PRODUCT_SPEC PLAY-009 — the rewind applied *because* a sleep timer stopped playback.
     *
     * Separate from [AutoRewind] because it answers a different question. Auto-rewind covers "you paused
     * and came back"; this one covers "you fell asleep", and the amount a listener wants for the second is
     * minutes rather than seconds. Telling them apart in the list is the difference between a history that
     * explains a five-minute jump and one that leaves somebody wondering.
     */
    SleepTimerRewind,
    ;

    /** `true` for the kinds that replaced a position, which is the set that has something to undo. */
    val isJump: Boolean
        get() = this == Seek || this == Skip || this == Chapter || this == AutoRewind || this == SleepTimerRewind

    companion object {
        /** PRODUCT_SPEC SYNC-001 — an unrecognized stored value reads back as the commonest kind. */
        fun parse(name: String?): PlaybackEvent = entries.firstOrNull { it.name == name } ?: Seek
    }
}

/**
 * One event.
 *
 * @property from where the listener was *before*, for the kinds that moved them. `null` for a marker —
 *   a pause did not come from anywhere.
 * @property to the position the event happened at, or landed on.
 * @property detail a second duration the event carries, if it has one: a sleep timer's length, or the
 *   remainder after an extension. `null` for everything else.
 */
data class PlaybackHistoryEntry(
    val id: String,
    val event: PlaybackEvent,
    val from: Duration?,
    val to: Duration,
    val detail: Duration?,
    val at: Instant,
) {
    /**
     * Where tapping this row goes.
     *
     * For a jump it is the position the jump replaced — the undo that a seek has never had. For a marker
     * it is the marker's own position, which is still worth returning to: "take me back to where I fell
     * asleep" is the single most useful thing this list can do.
     */
    val returnTo: Duration get() = from ?: to
}
