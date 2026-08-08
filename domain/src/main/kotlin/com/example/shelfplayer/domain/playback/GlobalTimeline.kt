package com.example.shelfplayer.domain.playback

import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.library.PlayableTrack
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-003 — converting between a book's timeline and a playlist's.
 *
 * ### The problem this solves
 *
 * A listener has one position: "four hours into the book". A player has two: which file, and how far
 * into it. Every position that crosses the boundary between the app and the player has to be converted,
 * and getting it wrong is a bug that appears only on multi-file books — a saved position that lands in
 * the wrong chapter, or a resume that restarts a file.
 *
 * ### Why nothing here sums durations
 *
 * The server sends each track's `startOffset` already on the global timeline; `multi-item-play.json`
 * settled that, with track two of a six-then-four-second book reporting `6`. So a conversion is a lookup
 * plus a subtraction, and never an accumulation.
 *
 * That is not a micro-optimisation. Track durations are fractional — the server sends
 * `1234.567891` — and an accumulated offset drifts from the server's by the rounding error of every
 * preceding track. On a forty-track book the drift is audible, and it lands in a *saved* position
 * (product priority 2).
 *
 * ### Excluded tracks
 *
 * [cursorFor] and [positionOf] work on the list they are given, and callers give them
 * [com.example.shelfplayer.core.model.library.PlaybackSession.playableTracks] — excluded tracks are
 * already gone. Their `startOffset` values are the server's, so a book with an excluded track in the
 * middle has a gap in its timeline: the app plays what remains, and a global position inside the gap
 * resolves to the start of the next track. No capture has an excluded track in it, so what the *server*
 * does with such a book's timeline is unverified (PRODUCT_SPEC 22.5); what is verified is that the app
 * does not play the file, which is what PLAY-003 requires.
 */
object GlobalTimeline {

    /**
     * Where the player should be, for a position in the book.
     *
     * @property index into the list handed in — a Media3 window index, not the server's track number.
     */
    data class Cursor(val index: Int, val offset: Duration)

    /**
     * The player position for [position] in the book.
     *
     * A position before the first track clamps to its start and one past the last clamps to the end of
     * the last: both are reachable — a corrupted stored position, a book that shrank when its files were
     * rescanned — and neither should be able to leave the player pointing at nothing.
     */
    fun cursorFor(tracks: List<PlayableTrack>, position: Duration): Cursor {
        if (tracks.isEmpty()) return Cursor(index = 0, offset = Duration.ZERO)
        // The last track whose start is at or before the position. `indexOfLast` rather than a binary
        // search: a book has tens of tracks, not thousands, and this runs on a seek.
        val index = tracks.indexOfLast { track -> track.startOffset <= position }.coerceAtLeast(0)
        val track = tracks[index]
        val offset = (position - track.startOffset).coerceIn(Duration.ZERO, track.duration)
        return Cursor(index, offset)
    }

    /**
     * The book position for a player position.
     *
     * The inverse of [cursorFor] for every position inside a track. It is deliberately *not* an exact
     * inverse in the gaps: a position that fell inside an excluded track comes back as the start of the
     * track that follows, which is where playback actually is.
     */
    fun positionOf(tracks: List<PlayableTrack>, index: Int, offset: Duration): Duration {
        val track = tracks.getOrNull(index) ?: return Duration.ZERO
        return positionOf(track.startOffset, offset.coerceAtMost(track.duration))
    }

    /**
     * The same conversion for a caller that holds the track's offset but not the list.
     *
     * That caller is the media service: it reads the offset off the playing item's metadata, because
     * after the app process is gone the playlist is the only place the fact still exists. One function
     * rather than the addition written twice — the second copy is the one that eventually disagrees.
     */
    fun positionOf(trackStartOffset: Duration, offset: Duration): Duration =
        trackStartOffset + offset.coerceAtLeast(Duration.ZERO)

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
}
