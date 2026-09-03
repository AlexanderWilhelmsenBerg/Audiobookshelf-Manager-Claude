package com.example.shelfplayer.domain.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.playback.AcknowledgedPause
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * PRODUCT_SPEC SYNC-002 — the last pause the server confirmed, and the moves that revoke it.
 *
 * ### What this replaced, and why the replacement is a different kind of thing
 *
 * Deciding "has another device moved this book" was attempted four times from evidence that could not
 * answer it — two clocks, a stopwatch on the pause, a persistence flag, and the player's own position.
 * Each produced a device run where Play resumed on a stale position, and the last one produced a log line
 * that contradicted itself: `Skipped the freshness check: this device has a position the server has not
 * taken stored=22461280ms player=22461280ms`, forty-six seconds after a `200` for that exact position
 * (`docs/risks.md` R-93).
 *
 * The evidence that *can* answer it is an agreement: a moment at which this device and the server were
 * demonstrably holding the same position. This class is the record of the most recent one.
 *
 * ### The four transitions
 *
 *  - [onPaused] — the player stopped at an exact position. That position becomes *pending*: a claim, not
 *    yet a fact, and it is deliberately not usable for anything until the server answers.
 *  - [onPositionAccepted] — a sync came back successful for a position. If it is the pending one, the
 *    agreement exists and the baseline becomes [AcknowledgedPause]. Any accepted sync counts, not only
 *    the pause's own: a pause whose upload failed and whose next 30-second tick succeeded is just as
 *    firmly agreed, and refusing it would have thrown away a fact the app already had.
 *  - [onLocalMove] — the listener seeked, skipped, crossed a track or started playing. Whatever was
 *    pending or acknowledged no longer describes where this device is at rest, so it is dropped and the
 *    generation moves on.
 *  - [onBookClosed] — a different book, or none. The baseline is per book and never crosses one.
 *
 * ### Why the generation is not decoration
 *
 * `onPositionAccepted` runs after a network round trip. In the meantime a seek can have replaced the
 * pending record, and — because positions repeat, and because a paused player syncs the same position
 * every thirty seconds — matching on the position alone is not enough to tell "this confirms my pause"
 * from "this confirms a pause two seeks ago". The generation is bumped by every mutation, so an
 * acknowledgement is only ever applied to the record that was current when the sync was requested. That
 * is checked explicitly rather than inferred, which is what makes the ordering safe rather than lucky.
 *
 * ### Thread safety, which this one does need
 *
 * Unlike [ListenedTime], this is written from the service's main-thread scope *after a suspension* and
 * read from the app's ViewModel scope, so two dispatches can genuinely interleave. Every access is
 * therefore inside [lock]; each is a handful of field comparisons, so contention is not a consideration.
 *
 * A singleton for the same reason `SessionSyncCoordinator` is one: `PlaybackService` declares no
 * `android:process`, so the service that writes this and the ViewModel that reads it are the same object
 * graph, and a baseline established by a pause from the notification is available to a Play from the
 * screen.
 */
@Singleton
class ResumeBaseline @Inject constructor() {

    private val lock = Any()

    private var record: Record? = null
    private var generation: Long = 0

    /**
     * The player came to rest for [bookId] at exactly [position].
     *
     * Returns the generation this pause was filed under, which the caller hands back to
     * [onPositionAccepted]. Nothing is trusted yet — see the header.
     */
    fun onPaused(bookId: LibraryItemId, position: Duration): Long = synchronized(lock) {
        generation += 1
        record = Record(bookId = bookId, position = position, generation = generation, acknowledged = false)
        generation
    }

    /**
     * The generation of whatever record is held for [bookId], acknowledged or not, or `null` for none.
     *
     * Read by the session sync **before** it sends, so the generation it hands back to
     * [onPositionAccepted] is the one that was current when the upload was built. A seek during the round
     * trip bumps the counter, and the acknowledgement is then refused rather than applied to a pause it
     * does not describe. That is the whole mechanism, and reading it here rather than at completion is what
     * makes it a guard instead of another read-decide race.
     */
    fun generationFor(bookId: LibraryItemId): Long? = synchronized(lock) {
        record?.takeIf { it.bookId == bookId }?.generation
    }

    /**
     * A sync for [bookId] came back successful holding [position].
     *
     * Promotes the pending pause to acknowledged when all three agree — the book, the generation, and the
     * position within [ACCEPTED_TOLERANCE]. Returns whether it did, so the caller can log the fact rather
     * than the attempt.
     *
     * The tolerance exists because the pause and the sync read the player independently: two consecutive
     * syncs of a *standing* player have been observed three milliseconds apart. It is far below any
     * position difference a person could produce.
     */
    fun onPositionAccepted(bookId: LibraryItemId, position: Duration, generation: Long): Boolean = synchronized(lock) {
        val pending = record ?: return false
        if (pending.generation != generation || this.generation != generation) return false
        if (pending.bookId != bookId) return false
        val drift = (pending.position.inWholeMilliseconds - position.inWholeMilliseconds).absoluteValue
        if (drift > ACCEPTED_TOLERANCE.inWholeMilliseconds) return false
        record = pending.copy(acknowledged = true)
        true
    }

    /**
     * The listener moved the book locally — a seek, a skip, a track boundary, or playback starting.
     *
     * Whatever was held is dropped rather than merely marked stale. A baseline that describes a position
     * the player has left is not a weaker fact; it is a wrong one, and the only safe answer to "is the
     * server ahead of us" without one is *do not move anything* (product priority 2).
     */
    fun onLocalMove() = synchronized(lock) {
        generation += 1
        record = null
    }

    /** A different book is loaded, or none. Same reasoning as [onLocalMove], one scope wider. */
    fun onBookClosed() = onLocalMove()

    /**
     * The acknowledged baseline for [bookId], or `null` when there is not one.
     *
     * `null` is every case in which the server's freshness cannot be judged: nothing has paused yet (a
     * cold start — where the position came from the server's own `/play` response anyway), the pause's
     * sync failed or has not answered, the listener has moved the book since, or the loaded book is not
     * this one. Each of them resumes locally, which is the direction that cannot lose a position.
     */
    fun acknowledged(bookId: LibraryItemId): AcknowledgedPause? = synchronized(lock) {
        val held = record ?: return null
        if (!held.acknowledged || held.bookId != bookId) return null
        AcknowledgedPause(bookId = held.bookId, position = held.position, generation = held.generation)
    }

    private data class Record(
        val bookId: LibraryItemId,
        val position: Duration,
        val generation: Long,
        val acknowledged: Boolean,
    )

    private companion object {
        /** Read-skew between the pause and the sync that uploads it. Milliseconds, not seconds. */
        val ACCEPTED_TOLERANCE: Duration = 1_000.milliseconds
    }
}
