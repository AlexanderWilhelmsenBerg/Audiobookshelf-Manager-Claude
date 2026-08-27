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

    /**
     * PRODUCT_SPEC PLAY-004 / SYNC-002 — the server moved this book, and this device did not.
     *
     * The device report asked for it: *"The history should also show the latest changes from the server."*
     * A book listened to in the web player, or on another phone, arrives here as a position that appeared
     * without anybody touching this device — which without a row is indistinguishable from the app losing
     * somebody's place. [PlaybackHistoryEntry.from] is where this device was, [PlaybackHistoryEntry.to] is
     * where the server says it is, and [PlaybackHistoryEntry.at] is the server's own timestamp rather than
     * the moment the refresh noticed, so the row sits in the timeline where it actually happened.
     */
    RemoteProgress,

    /**
     * PRODUCT_SPEC PLAY-004 — the book was marked finished, or un-marked, somewhere else.
     *
     * Split from [RemoteProgress] because it is a different thing to read. A position arriving from another
     * device is ordinary; a book turning up finished when you did not finish it is the kind of surprise a
     * history exists to explain.
     */
    RemoteFinished,

    /**
     * PRODUCT_SPEC PLAY-003 / PLAY-004 — a listening session **another device** recorded, as the server
     * reported it.
     *
     * The owner asked for this: *"I want to have it populated from events from audiobookshelf itself."*
     * [RemoteProgress] is the closest thing that existed and it is a reconstruction — a diff of stored
     * progress against a sync — which cannot see a book this device has never played, and collapses two
     * sessions between syncs into one row. This is the server's own entry instead: one per session.
     *
     * [PlaybackHistoryEntry.from] is where the session opened, [PlaybackHistoryEntry.to] where it got to,
     * [PlaybackHistoryEntry.detail] how much was actually listened — which is not the span, because a paused
     * session accrues none — and [PlaybackHistoryEntry.at] the server's own start time, so the row sits in
     * the timeline where it happened rather than where the fetch noticed it.
     *
     * **Only other devices' sessions become rows.** This phone's own sessions come back from the server too,
     * and they would duplicate the `Play` and `Pause` entries the player already writes.
     */
    ServerSession,
    ;

    /** `true` for the events that come from somewhere other than this device. */
    val isRemote: Boolean
        get() = this == RemoteProgress || this == RemoteFinished || this == ServerSession

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
