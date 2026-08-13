package com.example.shelfplayer.domain.playback

import com.example.shelfplayer.core.model.library.Chapter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-003 — chapters on a book's timeline.
 *
 * ### One timeline, since ADR-0016
 *
 * This used to convert between two timelines. A listener has one position — "four hours into the book" —
 * and a playlist-based player had two: which file, and how far into it. Every position crossing the
 * boundary between the app and the player had to be converted, and the conversion leaked: Media3 reports
 * the *current item's* position to every controller, so the notification described the file.
 *
 * A book is now one `ConcatenatingMediaSource2` window, so the player's position *is* the book's position
 * and there is nothing left to convert. `cursorFor` and `positionOf` are gone with the arithmetic they
 * did; what remains is the part that was never about files.
 *
 * ### Chapters are not tracks
 *
 * Chapters live on the book's timeline and are independent of file boundaries — one file can hold several
 * chapters, and one chapter can span several files. Everything here therefore takes a *position*, never a
 * track index, and would read the same if the book were a single file.
 */
object GlobalTimeline {

    /**
     * The chapter containing [position], or `null` when the book has none.
     *
     * Chapters are on the same global timeline as tracks and are independent of file boundaries — one
     * file can hold several, and one chapter can span several files. That is why this takes a position
     * rather than a track index.
     *
     * A position past the last chapter's end still returns the last chapter rather than `null`: the
     * final chapter's `end` and the book's duration can differ by a rounding, and "no chapter" at the
     * very end of a book would blank the chapter title just as the listener reaches it.
     */
    fun chapterAt(chapters: List<Chapter>, position: Duration): Chapter? {
        if (chapters.isEmpty()) return null
        return chapters.lastOrNull { chapter -> chapter.start <= position } ?: chapters.first()
    }

    /**
     * PRODUCT_SPEC PLAY-003 — where "next chapter" goes, or `null` at the last one.
     *
     * The start of the following chapter in time order, which is not necessarily the following element
     * of the list: a server may send them unordered, and PLAY-003's navigation has to be about the
     * book's timeline rather than about array positions.
     */
    fun nextChapterStart(chapters: List<Chapter>, position: Duration): Duration? =
        ordered(chapters).firstOrNull { chapter -> chapter.start > position }?.start

    /**
     * PRODUCT_SPEC PLAY-003 — where "previous chapter" goes.
     *
     * Two answers, and the split is what every media player does because it is what people expect:
     *
     *  - **more than [restartWithin] into the current chapter** — back to *this* chapter's start, so the
     *    button restarts what you are listening to;
     *  - **within the first few seconds** — back to the *previous* chapter, so pressing it twice moves
     *    back two chapters rather than sticking on one boundary.
     *
     * `null` only when there is nowhere to go: no chapters at all, or already at the very start of the
     * first one.
     */
    fun previousChapterStart(
        chapters: List<Chapter>,
        position: Duration,
        restartWithin: Duration = RESTART_WITHIN,
    ): Duration? {
        val ordered = ordered(chapters)
        if (ordered.isEmpty()) return null
        val current = ordered.lastOrNull { chapter -> chapter.start <= position } ?: return ordered.first().start
        if (position - current.start > restartWithin) return current.start
        return ordered.lastOrNull { chapter -> chapter.start < current.start }?.start
            ?: current.start.takeIf { position > it }
    }

    /** The index of the chapter containing [position] within the **time-ordered** list, or `null`. */
    fun chapterIndexAt(chapters: List<Chapter>, position: Duration): Int? {
        val ordered = ordered(chapters)
        if (ordered.isEmpty()) return null
        return ordered.indexOfLast { chapter -> chapter.start <= position }.takeIf { it >= 0 } ?: 0
    }

    /** Chapters in time order, which is the order every function here works in. */
    fun ordered(chapters: List<Chapter>): List<Chapter> = chapters.sortedBy { it.start }

    /**
     * How far into a chapter "previous" stops restarting it and starts going back one.
     *
     * Three seconds is long enough that a deliberate double-press moves two chapters and short enough
     * that pressing it a moment after a boundary restarts the chapter you meant.
     */
    private val RESTART_WITHIN: Duration = 3.seconds
}
