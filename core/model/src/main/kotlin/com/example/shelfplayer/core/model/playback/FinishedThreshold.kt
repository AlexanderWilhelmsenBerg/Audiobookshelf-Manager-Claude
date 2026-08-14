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
 * ### There is no app-side setting, and that is the whole design
 *
 * Each library on the server carries `markAsFinishedTimeRemaining`, and that value **is** the rule for its
 * books. This app holds no competing number. [Default] is a fallback for a library that reports none, not a
 * preference.
 *
 * It ended here after two earlier shapes — a setting, then a `max` of the setting and the library — and the
 * reason is that there is nothing on the server to synchronise a per-listener value *with*. The user object
 * carries no such field: `contracts/me.json` has no `settings` key at all. The only writable copy is the
 * library's own configuration, which belongs to the administrator and applies to every account that can see
 * the library. A per-device setting could therefore only ever *disagree* with the server, and the owner's
 * instruction was that the two should match. One number, read from the library, is the only way they can.
 *
 * ### Why the app does not write it back
 *
 * Recorded here because it is the first question a reader will have. `library.settings` carries **twelve**
 * fields and this app models one; the other eleven are the server's own scanning and matching behaviour,
 * including an ordered `metadataPrecedence` array and the filesystem watcher. No capture shows whether the
 * server merges a partial settings PATCH or replaces the object, so a write-back could silently discard
 * eleven settings this app deliberately does not understand — on a library shared with other people.
 * PRODUCT_SPEC 22.4 forbids relying on unobserved server behaviour, and this is the case it exists for.
 *
 * ### Was previously a constant, and lived in `:domain`
 *
 * Until PR 2 of the Phase 2 closeout this was `object FinishedThreshold` with a hard-coded 30 seconds, and
 * its own comment documented the gap and said it would close "in wave 3". It did not: the library's settings
 * were being parsed away in `LibraryDto` the whole time. It moved to `:core:model` in the same change, because
 * the rule now travels on [com.example.shelfplayer.core.model.library.Library], and the model module is the
 * one both the data layer and the screens can see.
 */
data class FinishedThreshold(
    /**
     * PRODUCT_SPEC PLAY-004 — the book's library's `markAsFinishedTimeRemaining`, or `null` for none.
     *
     * `null` is not zero. A library that has not set a rule has not asked for one, and reading that as
     * "finished with 0 seconds left" would silently be a *different* rule.
     */
    val library: Duration? = null,
) {
    /** The rule in force: the library's where it has one, [Default] otherwise. */
    val effective: Duration get() = library ?: Default

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
        /**
         * ADR-0013's number, used only where a library reports no rule of its own.
         *
         * Not a setting, and not a default anybody can see or change. Every Audiobookshelf library has
         * `markAsFinishedTimeRemaining` set — the server's own default is ten seconds — so in practice this
         * covers a library whose settings this app has not read yet: a row cached before database version 14,
         * or one written by a sync that predates the field being parsed.
         */
        val Default: Duration = 30.seconds

        /**
         * A library's `markAsFinishedTimeRemaining`, in the server's unit, as a rule this app can hold.
         *
         * **Seconds** on the wire and in the database column, so the conversion happens here rather than at
         * each of the three readers — the wire mapper, the entity mapper and the offline fixture. A negative
         * value is dropped rather than clamped: it is not a library asking for anything sensible, and
         * honouring it as zero would be inventing a rule the server does not have.
         *
         * Deliberately **not** bounded. A server may legitimately want ten seconds or ten minutes, and
         * second-guessing it here would be the arguing this design exists to stop.
         */
        fun libraryRule(seconds: Long?): Duration? = seconds?.takeIf { it >= 0 }?.seconds
    }
}
