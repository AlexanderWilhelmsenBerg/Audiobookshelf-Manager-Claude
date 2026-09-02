package com.example.shelfplayer.core.model.playback

import kotlin.time.Duration

/**
 * PRODUCT_SPEC SYNC-002 — what asking the server about this book's position produced.
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
 * [PlaybackEvent.ServerCheckUnavailable] mark the Play row that resumed under it.
 *
 * Only [Ahead] moves anything, and it carries the position to move to. The other two resume where the
 * player already is, which is product priority 2: a check that cannot prove the local position stale must
 * never replace it.
 */
sealed interface ExternalSessionCheck {

    /**
     * The server answered, and its stored position is somebody else's more recent work.
     *
     * [position] is the server's own `currentTime`, so adopting it is a **seek** rather than a reload —
     * the whole point of asking before audio starts. It may be *behind* the local position: an intentional
     * rewind on another client is newer activity even though its number is smaller, which is why the
     * timestamp decides and the magnitude never does.
     */
    data class Ahead(val position: Duration) : ExternalSessionCheck

    /**
     * The server answered, and there is nothing newer than what this device holds.
     *
     * Also the answer when this device has progress it has not yet uploaded: the local position is then the
     * truth by definition, and there is no need to ask at all.
     */
    data object Current : ExternalSessionCheck

    /**
     * The server was not reached, the read failed, it did not answer inside the cap, or the check was
     * cancelled before it answered.
     *
     * Playback resumes exactly as it would have without the check. The mark this leaves on the Play row is
     * the only trace that the guarantee was missing, which is why it is recorded even when the cancellation
     * came from the listener pressing something else.
     */
    data object Unavailable : ExternalSessionCheck

    /** `true` when Audiobookshelf answered — the two outcomes that carry a server fact rather than a gap. */
    val answered: Boolean get() = this !is Unavailable
}
