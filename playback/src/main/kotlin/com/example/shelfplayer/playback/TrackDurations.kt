package com.example.shelfplayer.playback

import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-003 / 22.4, ADR-0016, `docs/risks.md` R-61 — recovering a track length the server did
 * not give.
 *
 * ### The defect this serves
 *
 * `ConcatenatingMediaSource2` needs every track's duration up front, so a book with a track reporting zero
 * cannot be built as one timeline window and falls back to playing its first file. That fallback was
 * described as "a real degradation and the honest one" and it was not honest: the player's timeline became
 * one file while `queueFor` still handed it a whole-**book** start position and every writer still read
 * `currentPosition` as a book position. Resuming a 34-hour book at 4 hours seeks past the end of a
 * 20-minute file, and then each journal tick writes that file-relative position into the book's stored
 * progress. Product priority 2 is *do not lose progress*, and it was being rewritten silently.
 *
 * Two things fix it. This file is the first: make the fallback almost unreachable by computing the missing
 * length instead of giving up. `MediaItems.isSingleFileFallback` is the second, and covers what is left.
 *
 * ### Why the arithmetic is sound, and exactly how far
 *
 * The captured contracts pin a session's `duration` equal to the sum of its `audioTracks` durations —
 * `CapturedShapesTest` asserts it for a library item's `media`, and the two play fixtures
 * (`item-play`, `multi-item-play`) satisfy it directly, which `PlaybackSessionDurationTest` now pins. So
 * one unknown track's length is exactly `book total − sum of the known ones`.
 *
 * **The limits are refusals, not approximations.** [recovered] returns `null` rather than a guess when:
 *
 *  - **more than one track is unknown** — the remainder is a sum and cannot be split between them;
 *  - **any track is excluded** — and this is the one worth stating. No captured fixture has an excluded
 *    track, so whether a session's `duration` counts an excluded file's length is **not established**. If
 *    it does, the remainder is inflated by that file and the recovered track would be too long, which is
 *    the same coordinate corruption in a quieter form. PRODUCT_SPEC 22.4 says do not guess undocumented
 *    server behaviour, so this refuses instead — and if a capture ever settles it, this is the one place to
 *    change;
 *  - **the book total is missing or the remainder is not positive** — arithmetic that produced a
 *    non-positive length would fail `ConcatenatingMediaSource2`'s own assertion anyway, later and less
 *    legibly.
 *
 * ### Why a pure function over lists
 *
 * The same reason as `ControllerTrust.accessFor` and `RecentsPrivacy.canSuppressThumbnail`: the rule that a
 * test exercises has to be the rule that runs (`docs/risks.md` R-43). Nothing here touches Media3, a
 * `Bundle` or a player, so every branch is reachable from a JVM test — including the ones that only occur
 * on a server nobody has captured.
 */
internal object TrackDurations {

    /**
     * [durations] with a single unknown entry filled in from [bookTotal], or `null` when it cannot be.
     *
     * Returns [durations] unchanged when nothing needs repair, so a caller has one call rather than a
     * pre-check and a call that can disagree with it.
     *
     * @param durations the playable tracks' lengths, in order. A non-positive entry is "unknown".
     * @param bookTotal the session's own duration — the server's total for the book.
     * @param anyExcluded whether the session carried any excluded track at all. See the refusals above.
     */
    fun recovered(durations: List<Duration>, bookTotal: Duration, anyExcluded: Boolean): List<Duration>? {
        val unknown = durations.indices.filter { durations[it] <= Duration.ZERO }
        val known = durations.filter { it > Duration.ZERO }.fold(Duration.ZERO, Duration::plus)
        val remainder = bookTotal - known

        // One `when` rather than a ladder of guarded returns, so the refusals read as the list they are.
        return when {
            unknown.isEmpty() -> durations
            unknown.size > 1 -> null
            anyExcluded -> null
            bookTotal <= Duration.ZERO -> null
            remainder <= Duration.ZERO -> null
            else -> durations.toMutableList().apply { this[unknown.single()] = remainder }
        }
    }
}
