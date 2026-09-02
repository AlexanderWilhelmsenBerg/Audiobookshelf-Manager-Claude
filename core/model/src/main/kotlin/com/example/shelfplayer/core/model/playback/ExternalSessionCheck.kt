package com.example.shelfplayer.core.model.playback

/**
 * PRODUCT_SPEC SYNC-002 — what asking the server about a book's *other* sessions produced.
 *
 * ### Why an outcome rather than a boolean
 *
 * The check has three results and a boolean carries two. "Nothing newer" and "could not tell" both leave
 * the loaded position alone, so they were the same value — which meant a Play that resumed on a verified
 * answer and a Play that resumed because the network was gone looked identical afterwards. They are not
 * identical: the first is a guarantee and the second is a hope, and the difference is exactly what somebody
 * asks about when a position turns out to be wrong.
 *
 * So the outcome is carried out of the repository and written into the book's history, where
 * [PlaybackEvent.ServerCheckAhead], [PlaybackEvent.ServerCheckCurrent] and
 * [PlaybackEvent.ServerCheckUnavailable] give each of the three its own row.
 *
 * Only [Ahead] moves anything. The other two resume where the player already is, which is product priority
 * 2: a check that cannot prove the local position is stale must never replace it.
 */
enum class ExternalSessionCheck {
    /** The server answered, and another device used this book after BookWave's own session opened. */
    Ahead,

    /**
     * The server answered, and nothing newer exists — or nothing newer can be attributed to another device.
     *
     * Also the answer when the server was reached but BookWave's own session for this book is not among the
     * records it returned. There is then nothing to compare a remote session against, and "the loaded
     * position stands" is the only claim the evidence supports.
     */
    Current,

    /**
     * The server was not reached, the read failed part-way, or the check was cancelled before it answered.
     *
     * Playback resumes exactly as it would have without the check. The row this writes is the only trace
     * that the guarantee was missing, which is why it is written even when the cancellation came from the
     * listener pressing something else.
     */
    Unavailable,
    ;

    /** `true` when Audiobookshelf answered — the two outcomes that carry a server fact rather than a gap. */
    val answered: Boolean get() = this != Unavailable
}
