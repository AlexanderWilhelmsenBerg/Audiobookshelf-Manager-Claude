package com.example.shelfplayer.core.model.playback

import com.example.shelfplayer.core.model.library.FinishedRule
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * ADR-0013 — a book is finished when little enough of it remains, and the library gets a say.
 *
 * ### Time remaining, not a percentage
 *
 * PLAY-004's literal wording is "95%, configurable 90–99%", and 95% of a ten-hour book is **half an hour**
 * from the end, which is not what anyone means by finished. The requirement's intent is kept; its unit is
 * not. The deviation is recorded in ADR-0013 and was the project owner's decision, not an implementation
 * shortcut. The configurable range is therefore a duration — [Range], 5 to 120 seconds — matching the skip
 * intervals PLAY-007 already uses.
 *
 * ### The `max`, which is the whole design
 *
 * ADR-0013's rule:
 *
 * ```
 * finishedWhenRemaining = max(the user's setting, library.markAsFinishedTimeRemaining ?: 0s)
 * ```
 *
 * The asymmetry is deliberate. The server marks books finished by the library's numbers whether the app
 * reads them or not, so two rules that disagree make a book **oscillate** — finished in one place,
 * unfinished in the other, flipping every time either syncs. Taking the `max` means the app is never the
 * one saying "not finished" about a book the server has finished:
 *
 *  - the library **less** eager than the user's setting (the capture server, at 10 s against a default 30)
 *    — the app finishes first, sends `isFinished`, and the server accepts it;
 *  - the library **more** eager (configured at 60 s) — the library's number wins, and the two stay in step.
 *
 * ### The percentage, carried but unverified
 *
 * A library may also set `markAsFinishedPercentComplete`, and the same asymmetry applies: the app must not
 * be less eager than a server that finishes on a fraction either. So it is honoured — but **no capture has
 * produced a non-null value**, so this branch is written to the server's documented field rather than to an
 * observed response (PRODUCT_SPEC 22.5). It is guarded accordingly: a percentage outside `0..100` is
 * dropped by [FinishedRule.of] rather than clamped.
 *
 * ### Was previously a constant, and lived in `:domain`
 *
 * Until PR 2 of the Phase 2 closeout this was `object FinishedThreshold` with a hard-coded 30 seconds, and
 * its own comment documented this gap and said it would close "in wave 3". It did not. The library's
 * settings were being parsed away in `LibraryDto` the whole time.
 *
 * It moved here from `:domain` in the same change, because [Range] and [Default] now have three readers:
 * [PlaybackSettings], which carries the chosen value; `AppSettingsDataSource`, which clamps what it reads off
 * disk; and the settings screen, which offers the choices. `:core:datastore` cannot see `:domain`, so leaving
 * the bounds there would have meant a second copy of the numbers — and two copies of a range are one range
 * and one bug waiting for somebody to widen the other.
 */
data class FinishedThreshold(
    /** PRODUCT_SPEC SET-002 — the listener's own setting, within [Range]. */
    val configured: Duration = Default,
    /** PRODUCT_SPEC PLAY-004 — what the book's library asks for, or [FinishedRule.Unset]. */
    val library: FinishedRule = FinishedRule.Unset,
) {
    /**
     * The time-remaining rule actually in force: never less eager than the library's.
     *
     * `?: Duration.ZERO` rather than falling back to [configured] — a library with no opinion contributes
     * nothing to the `max`, which is the same thing said two ways but only one of them survives a reader
     * changing the other branch.
     */
    val effective: Duration get() = maxOf(configured, library.timeRemaining ?: Duration.ZERO)

    /**
     * Whether a book at [position] of [duration] counts as finished.
     *
     * A book whose duration is unknown is never finished by either rule: with no end to measure from,
     * "thirty seconds remaining" and "ninety-five per cent" both have no meaning, and guessing would mark
     * a book done that the listener has barely started (product priority 2).
     */
    fun isFinished(position: Duration, duration: Duration): Boolean {
        if (duration <= Duration.ZERO) return false
        if (duration - position <= effective) return true
        val percent = library.percentComplete ?: return false
        return position / duration >= percent
    }

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
    }
}
