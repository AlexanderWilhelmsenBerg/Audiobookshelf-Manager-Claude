package com.example.shelfplayer.domain.playback

import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.playback.AutoRewind
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-009 — where a rewind after a pause actually lands.
 *
 * Pure arithmetic, separate from the player, for the same reason [GlobalTimeline] is: this is the part that
 * can be wrong in a way nobody notices — a rewind that crosses into the previous chapter is a listener
 * hearing the end of a scene they already finished, and it looks like a bug in the position rather than in a
 * feature they may not remember turning on.
 */
object AutoRewindMath {

    /**
     * The position to resume at, given where playback stopped and how long the pause was.
     *
     * Returns [from] unchanged when nothing should move, so a caller can seek unconditionally and a
     * disabled setting costs one comparison rather than a branch at every call site.
     *
     * ### The clamp is the requirement
     *
     * PLAY-009: "rewind cannot move before chapter/book start". A rewind that crossed a chapter boundary
     * would replay the end of a chapter the listener finished, which is worse than not rewinding at all —
     * the whole feature exists to recover the last few seconds of context, and context does not span a
     * scene break.
     *
     * With no chapter metadata the book's start is the only floor there is, which is the graceful
     * degradation PLAY-008 asks for in its own case and the same answer applies here.
     */
    fun resumeAt(
        from: Duration,
        pausedFor: Duration,
        settings: AutoRewind,
        chapters: List<Chapter> = emptyList(),
    ): Duration {
        val amount = settings.amountFor(pausedFor)
        if (amount <= Duration.ZERO) return from
        val floor = GlobalTimeline.chapterAt(chapters, from)?.start ?: Duration.ZERO
        // `coerceAtLeast(floor)` and then `coerceAtMost(from)`: a chapter that starts *after* the position —
        // which malformed metadata can produce — must not move a listener forwards.
        return (from - amount).coerceAtLeast(floor).coerceAtMost(from).coerceAtLeast(Duration.ZERO)
    }

    /**
     * How far [resumeAt] actually moved, which is what an "undo" has to put back.
     *
     * Derived rather than returned alongside the position, because the two must not be able to disagree:
     * a caller that clamped the position and then used the unclamped amount to undo would leave the
     * listener ahead of where they paused.
     */
    fun appliedAmount(from: Duration, resumedAt: Duration): Duration = (from - resumedAt).coerceAtLeast(Duration.ZERO)
}
