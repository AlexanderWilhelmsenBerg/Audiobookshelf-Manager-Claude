package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.LibraryItemId
import kotlin.time.Duration

/**
 * PRODUCT_SPEC SYNC-002 — adopting another device's position, **on the player that actually owns it**.
 *
 * ### Why this moved out of the controller, which is the correction
 *
 * The first version issued the seek through `MediaController` and then, a second later, read
 * `MediaController.currentPosition` back to see whether it had taken. That verification cannot work, and a
 * device run showed why: the controller is an IPC proxy, so both the command and the read cross a binder to
 * the session and back. A seek that the session accepted and the player dropped, a seek that arrived while
 * the player was still idle, and a seek that landed correctly all produce the same answer from the same
 * proxy — the read is a report of what the session *believes*, not of what the player *did*. Re-reading the
 * thing that told you the lie is not a check.
 *
 * The current architecture has no private resume command. Standard Media3 Play reaches
 * [ResumeFreshnessPlayer], the coordinator decides whether the paused book stays local or adopts remote
 * progress, and `PlaybackService` performs any adopted movement on its service-owned
 * [androidx.media3.exoplayer.ExoPlayer] before audio starts. The service waits for that player's own position
 * discontinuity, confirms where the seek landed, revalidates request ownership, and only then calls Play.
 *
 * ### Why the ordering is a pure function over a seam
 *
 * `ExoPlayer` cannot be constructed cheaply in a unit test and `MediaController` is final, which is how the
 * old multi-coroutine defect survived review. [ResumeTarget] is exactly the player-side observations and
 * commands this operation needs, so `AtomicResumeTest` can assert *prepare, seek, confirm, revalidate, then
 * play* — and, more importantly, that **no play happens** when the seek did not land.
 */
internal interface ResumeTarget {

    /** The book the player is loaded with, or `null` when it holds nothing. */
    fun loadedBookId(): LibraryItemId?

    /** `true` when the player is idle or holding an error — the two states `play()` cannot leave. */
    fun needsPreparing(): Boolean

    fun prepare()

    /**
     * Seeks to [position] and suspends until the player reports where it landed, or [timeout] elapses.
     *
     * The answer is the player's **own** account of the seek — its position discontinuity, falling back to
     * its live position if no discontinuity arrives inside the cap. `null` means the player never came back
     * at all, which is a failure rather than a slow success: audio must not start on an unverified position.
     */
    suspend fun seekAndAwait(position: Duration, timeout: Duration): Duration?

    /** Starts audio only if the freshness request still owns playback at this exact moment. */
    suspend fun playIfCurrent(): Boolean
}

/** What [ResumeTarget.seekAndResume] did, in the order the failures are worth telling apart. */
internal enum class ResumeOutcome {
    /** Seeked, confirmed, and playing. The only outcome in which audio was started. */
    Resumed,

    /** The player holds nothing, so there was nothing to seek. */
    NotLoaded,

    /** The player is on a different book — a Play that raced a book change. Nothing was touched. */
    WrongBook,

    /**
     * The seek did not arrive where it was sent, or the player never reported it.
     *
     * Playback is deliberately **not** started: resuming from the position the listener was trying to leave
     * is the exact defect this path exists to remove. The service leaves the book paused rather than opening
     * a second server session whose state-changing response could outlive this request's ownership.
     */
    SeekLost,

    /** A newer command took ownership while the adopted seek was being confirmed. */
    Superseded,
}

/**
 * Adopts [target] for [bookId], and starts audio only if the player confirms it got there.
 *
 * The order, and why each step cannot move:
 *
 *  1. **the right book** — a Play that raced a book change must not seek the new book to the old one's
 *     position;
 *  2. **prepare if needed** — a seek on an idle player is silently discarded, so this cannot come after;
 *  3. **seek, and wait for the player's own answer** — before any audio, so nothing is heard from the
 *     position being left behind;
 *  4. **confirm within [tolerance]** — nothing is playing yet, so the landed position is still the seek's
 *     result rather than the seek's result plus however long the check took. That is what lets this be a
 *     tolerance at all; the earlier post-play version had to compare two distances instead;
 *  5. **revalidate ownership and play** — last, in one operation, so a newer Pause/seek/Stop that
 *     arrived during seek confirmation cannot be undone by this older Play.
 *
 * Negative targets are clamped by the implementation rather than rejected: a server that reports a nonsense
 * position should start the book, not fail the resume (product priority 1).
 */
internal suspend fun ResumeTarget.seekAndResume(
    bookId: LibraryItemId,
    target: Duration,
    tolerance: Duration,
    timeout: Duration,
): ResumeOutcome {
    refuse(bookId)?.let { return it }
    if (needsPreparing()) prepare()
    val landed = seekAndAwait(target, timeout)
    if (landed == null || (landed - target).absoluteValue > tolerance) return ResumeOutcome.SeekLost
    if (!playIfCurrent()) return ResumeOutcome.Superseded
    return ResumeOutcome.Resumed
}

/**
 * The two reasons to touch nothing at all, checked before anything is touched.
 *
 * Split out because a resume with five exits reads as five possible endings; it has two refusals and one
 * course of action, and this is which is which.
 */
private fun ResumeTarget.refuse(bookId: LibraryItemId): ResumeOutcome? {
    val loaded = loadedBookId() ?: return ResumeOutcome.NotLoaded
    return ResumeOutcome.WrongBook.takeIf { loaded != bookId }
}
