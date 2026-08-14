package com.example.shelfplayer.core.model.playback

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * ADR-0013 — a book is finished when little enough of it remains, and **the server decides how little.**
 *
 * ### Time remaining, not a percentage
 *
 * PLAY-004's literal wording is "95%, configurable 90–99%", and a percentage of a long book is a long time:
 * 95% of a hundred-hour book leaves **five hours** to go. The project owner rejected the unit twice, in those
 * terms, and this type has no percentage in it at all — not a disabled one, not an unread field. The
 * requirement's intent is kept; its unit is not, and ADR-0013 records the deviation.
 *
 * ### The library's value is inherited, not merged
 *
 * A library on the server carries `markAsFinishedTimeRemaining`, and where it does, **that is the number this
 * app uses**. [configured] applies only to a library that sets none.
 *
 * This replaced a `max` of the two, and the owner's instruction was the reason: *"instead of fighting the
 * server, have them merge — inherit from the web interface."* The `max` was the weaker idea. It bounded the
 * disagreement rather than removing it: with the app at 30 s against a library's 10 s, a book was finished
 * here twenty seconds before the web interface agreed, and a listener switching between the two saw a book
 * that changed state depending on where they looked. Inheriting means there is one rule for one book, and it
 * is the one the server's own interface shows.
 *
 * What the `max` was protecting against is still handled, just not here: a book the server reports as
 * `isFinished` is finished regardless of position, and a locally finished book is never quietly un-finished
 * (`DefaultPlaybackRepository`). So the app cannot contradict the server in either direction.
 *
 * ### Was previously a constant, and lived in `:domain`
 *
 * Until PR 2 of the Phase 2 closeout this was `object FinishedThreshold` with a hard-coded 30 seconds, and
 * its own comment documented the gap and said it would close "in wave 3". It did not. The library's settings
 * were being parsed away in `LibraryDto` the whole time.
 *
 * It moved here from `:domain` in the same change, because [Range] and [Default] now have three readers:
 * [PlaybackSettings], which carries the chosen value; `AppSettingsDataSource`, which clamps what it reads off
 * disk; and the settings screen, which offers the choices. `:core:datastore` cannot see `:domain`, so leaving
 * the bounds there would have meant a second copy of the numbers — and two copies of a range are one range
 * and one bug waiting for somebody to widen the other.
 */
data class FinishedThreshold(
    /** PRODUCT_SPEC SET-002 — the listener's own setting, used where the book's library sets none. */
    val configured: Duration = Default,
    /**
     * PRODUCT_SPEC PLAY-004 — the book's library's `markAsFinishedTimeRemaining`, or `null` for none.
     *
     * `null` is not zero. A library that has not set a rule has not asked for one, and reading that as
     * "finished with 0 seconds left" would silently be a *different* rule.
     */
    val library: Duration? = null,
) {
    /** The rule in force: the library's where it has one, the listener's setting otherwise. */
    val effective: Duration get() = library ?: configured

    /** Whether [effective] came from the server, which is the thing a settings screen has to be able to say. */
    val isInherited: Boolean get() = library != null

    /**
     * Whether a book at [position] of [duration] counts as finished.
     *
     * A book whose duration is unknown is never finished: with no end to measure from, "thirty seconds
     * remaining" has no meaning, and guessing would mark a book done that the listener has barely started
     * (product priority 2).
     */
    fun isFinished(position: Duration, duration: Duration): Boolean =
        duration > Duration.ZERO && duration - position <= effective

    companion object {
        /** ADR-0013's number, and the default the setting starts at. */
        val Default: Duration = 30.seconds

        /**
         * PRODUCT_SPEC SET-002 / ADR-0013 — the configurable span, as a duration.
         *
         * Five seconds is the shortest that is not just "the very end"; two minutes is long enough for a
         * listener who counts credits and an afterword as not-the-book. Wider than that stops being a
         * threshold: at ten minutes an hour-long book is finished six sixths of the way through.
         */
        val Range: ClosedRange<Duration> = 5.seconds..120.seconds

        /**
         * What the settings screen offers, matching `SkipIntervals.Presets`.
         *
         * The same eight numbers as the skip intervals, deliberately: they span the same range, and a user
         * who has already learned that 30 is the middle of one list should not meet a different arithmetic
         * in the next.
         */
        val Presets: List<Duration> = listOf(5, 10, 15, 30, 45, 60, 90, 120).map { it.seconds }

        /** Clamps a stored or typed value into [Range], so a bad row cannot produce a strange rule. */
        fun coerce(value: Duration): Duration = value.coerceIn(Range.start, Range.endInclusive)

        /**
         * A library's `markAsFinishedTimeRemaining`, in the server's unit, as a rule this app can hold.
         *
         * **Seconds** on the wire and in the database column, so the conversion happens here rather than at
         * each of the three readers — the wire mapper, the entity mapper and the offline fixture. A negative
         * value is dropped rather than clamped: it is not a library asking for anything sensible, and
         * honouring it as zero would be inventing a rule the server does not have.
         *
         * Deliberately **not** coerced into [Range]. That range bounds what a *listener* may choose; the
         * server may legitimately want ten seconds or ten minutes, and overriding it here would be the
         * arguing this design exists to stop.
         */
        fun libraryRule(seconds: Long?): Duration? = seconds?.takeIf { it >= 0 }?.seconds
    }
}
