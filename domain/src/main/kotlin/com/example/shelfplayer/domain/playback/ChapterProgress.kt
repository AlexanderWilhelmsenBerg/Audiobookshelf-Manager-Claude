package com.example.shelfplayer.domain.playback

import com.example.shelfplayer.core.model.library.Chapter
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-003 — how far through the *current chapter* a listener is.
 *
 * ### Why this exists next to the book's own progress
 *
 * The book's bar answers "how much of this is left"; it does not answer "can I finish this bit before I
 * park", which is the question somebody actually asks before deciding to stop. On a twenty-hour book the
 * book's bar barely moves within a chapter, so it cannot answer it even approximately.
 *
 * ### Why it is here rather than in the composable
 *
 * It is arithmetic with edge cases — a zero-length chapter, a position before the first chapter starts, a
 * position past the last chapter's end — and every one of them is a division or a negative duration that
 * would render as nonsense. Arithmetic that can be wrong belongs where it can be tested without a screen.
 *
 * @property ordinal 1-based, in **time** order — what "Chapter 3 of 24" means to a reader, which is not
 *   necessarily the server's own `index` if it sent them unordered.
 * @property fraction `0.0..1.0`, and `0` for a chapter with no length rather than a division by zero.
 */
data class ChapterProgress(
    val chapter: Chapter,
    val ordinal: Int,
    val count: Int,
    val elapsed: Duration,
    val remaining: Duration,
    val fraction: Float,
) {
    companion object {
        /**
         * Where [position] falls in its chapter, or `null` when the book has no chapter metadata.
         *
         * `null` rather than a zero-valued snapshot: "no chapters" and "at the start of chapter one" are
         * different things, and a caller that cannot tell them apart draws an empty bar for a book that
         * has none.
         */
        fun at(chapters: List<Chapter>, position: Duration): ChapterProgress? {
            val ordered = GlobalTimeline.ordered(chapters)
            val index = GlobalTimeline.chapterIndexAt(ordered, position) ?: return null
            val chapter = ordered[index]
            val length = (chapter.end - chapter.start).coerceAtLeast(Duration.ZERO)
            // Clamped at both ends. Before the first chapter's start is reachable on a book whose metadata
            // begins a few seconds in; past the last chapter's end is reachable because a final chapter's
            // `end` and the book's duration differ by a rounding.
            val elapsed = (position - chapter.start).coerceIn(Duration.ZERO, length)
            return ChapterProgress(
                chapter = chapter,
                ordinal = index + 1,
                count = ordered.size,
                elapsed = elapsed,
                remaining = length - elapsed,
                fraction = if (length <= Duration.ZERO) 0f else (elapsed / length).toFloat().coerceIn(0f, 1f),
            )
        }
    }
}
