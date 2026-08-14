package com.example.shelfplayer.core.model.library

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-004 / ADR-0013 — when the **library** says a book is finished.
 *
 * `GET /api/libraries` carries a `settings` object, and two of its fields answer this question. From
 * `libraries.json`, captured against Audiobookshelf 2.36.0:
 *
 * ```
 * "markAsFinishedTimeRemaining": 10,
 * "markAsFinishedPercentComplete": null
 * ```
 *
 * Both were parsed away until now, which is the gap ADR-0013 recorded and `FinishedThreshold`'s own
 * comment had been advertising since wave 2.
 *
 * ### Why the app has to know
 *
 * The server marks books finished by these numbers whether the app reads them or not. Two rules for one
 * question, disagreeing, make a book **oscillate**: finished in one place, unfinished in the other,
 * flipping every time either syncs. ADR-0013's answer is that the app is never *less* eager than the
 * server, which is why [FinishedThreshold] takes a `max` rather than picking a side.
 *
 * ### Both fields are nullable, and that is the server's meaning
 *
 * `null` is not zero. A library with no `markAsFinishedTimeRemaining` has not asked for a time rule, and
 * treating that as "finished with 0 seconds left" would silently be a *different* rule. `null` therefore
 * means "this library has no opinion", and the app's own setting stands alone.
 *
 * @property timeRemaining how close to the end the library calls finished, or `null` for no opinion.
 * @property percentComplete the library's percentage rule as a fraction in `0.0..1.0`, or `null`. **No
 *   capture has ever produced a non-null value** — every fixture reads `null` — so it is carried and
 *   applied but is unverified against a real server (PRODUCT_SPEC 22.5), which is stated in ADR-0013.
 */
data class FinishedRule(val timeRemaining: Duration? = null, val percentComplete: Double? = null) {

    companion object {
        /** A library that has said nothing, which is what an un-synced or podcast library looks like. */
        val Unset = FinishedRule()

        /**
         * The server's units, converted once.
         *
         * `markAsFinishedTimeRemaining` is **seconds** and `markAsFinishedPercentComplete` is a
         * **percentage** — 95 rather than 0.95 — so the conversion happens here rather than at each
         * reader. Out-of-range and negative values are dropped rather than clamped: a percentage of 400
         * is not a library asking for anything sensible, and honouring it would mark every book finished.
         */
        fun of(timeRemainingSeconds: Long?, percentComplete: Double?): FinishedRule = stored(
            timeRemainingSeconds = timeRemainingSeconds,
            fractionComplete = percentComplete?.div(PERCENT_MAX),
        )

        /**
         * The same rule, read back from storage, where the fraction is already a fraction.
         *
         * A second factory rather than a second division. The database column holds this type's own units —
         * see `LibraryEntity.finishedFractionComplete` — so a reader that went through [of] would divide by a
         * hundred a second time and silently turn a 95% rule into a 0.95% one, which marks every book
         * finished at the first write. Both readers of the stored columns come through here.
         *
         * Still validated, not merely trusted: a row from a build whose bounds differed is a row this build
         * has to open, and out-of-range is dropped rather than clamped for the reason [of] gives.
         */
        fun stored(timeRemainingSeconds: Long?, fractionComplete: Double?): FinishedRule = FinishedRule(
            timeRemaining = timeRemainingSeconds?.takeIf { it >= 0 }?.seconds,
            percentComplete = fractionComplete?.takeIf { it > 0.0 && it <= 1.0 },
        )

        private const val PERCENT_MAX = 100.0
    }
}
