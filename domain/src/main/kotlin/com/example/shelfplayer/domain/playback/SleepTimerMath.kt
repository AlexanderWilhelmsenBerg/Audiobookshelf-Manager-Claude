package com.example.shelfplayer.domain.playback

import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-008 — the two calculations behind a sleep timer, with no Android in them.
 *
 * They live here rather than inside the controller so they can be tested at every edge without a
 * player, a sensor or a clock — which is what the interesting cases need. A timer that fades to silence
 * one second early is not something a device test finds.
 */
object SleepTimerMath {

    /**
     * How long a fixed timer has left.
     *
     * Clamped at zero rather than allowed to go negative: a negative remaining time renders as
     * "-3 min" in a notification, and every caller would have to remember to clamp it.
     */
    fun remainingUntil(deadline: Duration, elapsed: Duration): Duration =
        (deadline - elapsed).coerceAtLeast(Duration.ZERO)

    /**
     * How long an end-of-chapter timer has left, from the book position.
     *
     * PRODUCT_SPEC PLAY-008 — "end-of-chapter handles malformed or absent chapters gracefully". Three
     * things count as ungraceful and each is handled rather than thrown:
     *
     *  - **no chapters at all**, which is normal for a self-hosted book with no metadata;
     *  - **a position past every chapter**, which happens at the very end of a book whose last chapter
     *    ends a rounding before the duration;
     *  - **a chapter whose `end` is at or before its `start`**, which a bad tag can produce.
     *
     * All three answer `null`, meaning "this book cannot have an end-of-chapter timer". The caller says
     * so, which is the graceful behaviour — the alternative, silently substituting some duration, sets a
     * timer the listener did not ask for.
     *
     * @param skip how many chapter boundaries to look past. `0` is the current chapter's end; `1` is the
     *   next one, which is what restarting an end-of-chapter timer means — the current boundary is
     *   already the one it would stop at, so "restart" has to reach further or it does nothing.
     */
    fun remainingToChapterEnd(chapters: List<Chapter>, position: Duration, skip: Int = 0): Duration? {
        if (chapters.isEmpty()) return null
        val ordered = chapters.sortedBy { it.start }
        val ends = ordered
            .filter { chapter -> chapter.end > chapter.start && chapter.end > position }
            .map { it.end }
        val target = ends.getOrNull(skip.coerceAtLeast(0)) ?: return null
        return (target - position).coerceAtLeast(Duration.ZERO)
    }

    /**
     * The player volume for a timer with [remaining] left, fading over [fade].
     *
     * `1.0` until the fade window, then a straight ramp to `0.0`. Linear rather than logarithmic, and
     * deliberately: a fade to sleep is not a mix, and a listener drifting off is not judging its
     * smoothness. A perceptual curve here would be a decision to defend for no gain anyone can hear.
     *
     * A fade of zero — or a longer fade than the timer — gives full volume until the moment it stops,
     * which is the honest reading of "no fade".
     */
    fun fadeVolume(remaining: Duration, fade: Duration): Float = when {
        fade <= Duration.ZERO -> 1f
        remaining >= fade -> 1f
        remaining <= Duration.ZERO -> 0f
        else -> (remaining.inWholeMilliseconds.toFloat() / fade.inWholeMilliseconds).coerceIn(0f, 1f)
    }

    /**
     * The length a restarted timer runs for.
     *
     * A fixed timer restarts to its own length — the listener set thirty minutes, and shaking the phone
     * gives them thirty minutes again. An end-of-chapter timer has no length of its own, so `null` says
     * "recompute from the book", which is what the controller does.
     */
    fun restartLength(mode: SleepTimerMode): Duration? = when (mode) {
        is SleepTimerMode.Fixed -> mode.length
        SleepTimerMode.EndOfChapter -> null
    }
}
