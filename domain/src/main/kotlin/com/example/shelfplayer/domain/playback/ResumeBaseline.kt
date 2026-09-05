package com.example.shelfplayer.domain.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.playback.AcknowledgedPause
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * PRODUCT_SPEC SYNC-002 — the last position the server and this player are known to agree on.
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
 * ### The five transitions
 *
 *  - [stageServerPosition] — `/play` has returned the position the incoming session should start at. It is
 *    only staged here: the player has not transitioned to that item yet, so claiming an agreement already
 *    would put server truth in front of player truth. [onBookClosed] consumes the staged value at the actual
 *    item transition and only then turns it into an acknowledged baseline.
 *  - [onPaused] — the player stopped at an exact position. That position becomes *pending*: a claim, not
 *    yet a fact, and it is deliberately not usable for anything until the server answers.
 *  - [onPositionAccepted] — a sync came back successful for a position. If it is the pending one, the
 *    agreement exists and the baseline becomes [AcknowledgedPause]. Any accepted sync counts, not only
 *    the pause's own: a pause whose upload failed and whose next 30-second tick succeeded is just as
 *    firmly agreed, and refusing it would have thrown away a fact the app already had.
 *  - [onLocalMove] — the listener seeked, skipped, crossed a track or started playing. Whatever was
 *    pending or acknowledged no longer describes where this device is at rest, so it is dropped and the
 *    generation moves on.
 *  - [onBookClosed] — a different item is loaded, or none. The old baseline is closed; when a `/play`
 *    position was staged for the incoming item, that server-confirmed position becomes the new baseline at
 *    this same transition.
 *
 * ### Why a server-opened paused book needs a baseline
 *
 * A profile switch restores the incoming account's last book paused. The old implementation cleared the
 * previous baseline during the switch and never established another one because the restored player never
 * went through a play -> pause transition. If the realtime socket was reconnecting while another device
 * moved that book, the next Play therefore skipped the freshness check and started uploading the older
 * restored position. Staging the `/play` start and promoting it at the item transition closes that gap
 * without treating a merely cached Room row as proof.
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
 * `android:process`, so the service that promotes/invalidates this and the ViewModel that reads it are the
 * same object graph. `BookChanges` only stages the position `/play` returned; the player transition remains
 * the point at which it becomes an agreement.
 */
@Singleton
class ResumeBaseline @Inject constructor() {

    private val lock = Any()

    private var record: Record? = null
    private var stagedServerPosition: StagedServerPosition? = null
    private var generation: Long = 0

    /**
     * Stages the position `/play` returned for the item that is about to be handed to the player.
     *
     * [position] is nullable deliberately. A single-file fallback cannot represent a book-global server
     * position on the player's timeline; passing `null` clears any older staging rather than allowing a
     * previous open to leak into this transition.
     *
     * This is not yet an [AcknowledgedPause]. The player has not transitioned, so the only fact here is
     * what the server said. [onBookClosed] is the player-side half of the handshake.
     */
    fun stageServerPosition(bookId: LibraryItemId, position: Duration?) = synchronized(lock) {
        stagedServerPosition = position?.let { StagedServerPosition(bookId = bookId, position = it) }
    }

    /**
     * The player came to rest for [bookId] at exactly [position].
     *
     * Returns the generation this pause was filed under, which the caller hands back to
     * [onPositionAccepted]. Nothing is trusted yet — see the header.
     */
    fun onPaused(bookId: LibraryItemId, position: Duration): Long = synchronized(lock) {
        generation += 1
        stagedServerPosition = null
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
        stagedServerPosition = null
        record = null
    }

    /**
     * Closes the old book at the actual player transition and consumes the server position staged for the
     * incoming item, if there is one.
     *
     * This method is also used when the player becomes empty or a book ends. In those cases nothing was
     * staged, so the result is simply no baseline. The staged value is consumed exactly once, which keeps a
     * failed/abandoned open from becoming evidence for a later transition.
     */
    fun onBookClosed() = synchronized(lock) {
        generation += 1
        val incoming = stagedServerPosition
        stagedServerPosition = null
        record = incoming?.let {
            Record(
                bookId = it.bookId,
                position = it.position,
                generation = generation,
                acknowledged = true,
            )
        }
    }

    /**
     * The acknowledged baseline for [bookId], or `null` when there is not one.
     *
     * `null` means the server's freshness cannot safely be judged: a pause has not been acknowledged, the
     * listener moved the book locally, the loaded item cannot represent the server's book-global position,
     * or the baseline belongs to another book. Each case resumes locally, which is the direction that cannot
     * throw away the listener's own unsynced move.
     */
    fun acknowledged(bookId: LibraryItemId): AcknowledgedPause? = synchronized(lock) {
        val held = record ?: return null
        if (!held.acknowledged || held.bookId != bookId) return null
        AcknowledgedPause(bookId = held.bookId, position = held.position, generation = held.generation)
    }

    private data class StagedServerPosition(val bookId: LibraryItemId, val position: Duration)

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
