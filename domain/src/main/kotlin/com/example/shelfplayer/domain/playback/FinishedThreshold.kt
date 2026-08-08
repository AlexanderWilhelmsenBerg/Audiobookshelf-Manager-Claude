package com.example.shelfplayer.domain.playback

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * ADR-0013 — a book is finished when 30 seconds or less remain.
 *
 * Time remaining, not a percentage. PLAY-004's literal wording is "95%, configurable 90–99%", and 95%
 * of a ten-hour book is **half an hour** from the end, which is not what anyone means by finished. The
 * requirement's intent is kept; its unit is not. The deviation is recorded in ADR-0013 and was the
 * project owner's decision, not an implementation shortcut.
 *
 * ### What is not here yet
 *
 * ADR-0013's full rule is `max(30s, library.markAsFinishedTimeRemaining)`, so that the app is never
 * *less* eager than the server and a book cannot oscillate between finished and unfinished as the two
 * disagree. The library setting is in the committed `libraries.json` and is **not yet modelled** — the
 * `Library` type has no such field — so this applies the app's half of the rule only. On the capture
 * server the setting reads 10 seconds, which is less eager than 30, so the `max` would be the constant
 * anyway; on a library configured at 60 it would not, and that is the gap. It closes in wave 3, where
 * the position is synced and the disagreement would actually become visible.
 */
object FinishedThreshold {

    /** ADR-0013. A configurable 5–120 second range is SET-002's, once a setting exists to hold it. */
    val Remaining: Duration = 30.seconds

    /**
     * Whether a book at [position] of [duration] counts as finished.
     *
     * A book whose duration is unknown is never finished by this rule: with no end to measure from,
     * "30 seconds remaining" has no meaning, and guessing would mark a book done that the listener has
     * barely started (product priority 2).
     */
    fun isFinished(position: Duration, duration: Duration): Boolean =
        duration > Duration.ZERO && duration - position <= Remaining
}
