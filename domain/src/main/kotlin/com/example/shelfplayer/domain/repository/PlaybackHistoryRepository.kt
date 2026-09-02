package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.playback.ExternalSessionCheck
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.PlaybackHistoryEntry
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-003 / PLAY-008 — what has happened to a book, and where.
 *
 * A seek is the one playback action with no undo: the position it replaced is gone the moment it lands, and
 * "somewhere around eleven hours" is not a position on a thirty-hour book. This is what makes it reversible —
 * and, since a device run asked for it, what makes a pause and a sleep timer visible too.
 *
 * Nothing here fails in a way the user should see. Recording an event is a side effect of something that has
 * already happened, and it must not be undone because a row could not be written (product priority 1).
 */
interface PlaybackHistoryRepository {

    /** This book's events, newest first. Empty for a book nobody has played. */
    fun observe(bookId: LibraryItemId, limit: Int = DEFAULT_LIMIT): Flow<List<PlaybackHistoryEntry>>

    /**
     * Writes one event down.
     *
     * @param from where the listener was before, for the kinds that moved them; `null` for a marker.
     * @param detail a second duration the event carries — a sleep timer's length, say. `null` otherwise.
     * @param at when it happened, for an event that did not happen *now*. The only caller that supplies it
     *   is the one recording a change the **server** made: the row belongs where the change happened, not
     *   where the refresh that noticed it happened, and a sync after a night's sleep would otherwise stack a
     *   week of other devices' listening at the top of the list. `null` means the clock.
     * @param owner PRODUCT_SPEC 6.5 — the profile the event belongs to, or `null` for the active one. The
     *   player names it from the loaded book's own extras, so a pause that arrives while a profile switch is
     *   in flight is filed against whoever was listening rather than whoever is signed in a moment later.
     *   Same reasoning as [PlaybackRepository.recordPosition]; a history row on a stranger's book is a
     *   smaller loss than a position on one, but it is the same boundary.
     */
    suspend fun record(
        bookId: LibraryItemId,
        event: PlaybackEvent,
        from: Duration?,
        to: Duration,
        detail: Duration? = null,
        at: Instant? = null,
        owner: ProfileId? = null,
    )

    /**
     * Asks the server whether another device has touched [bookId] since BookWave's own session began.
     *
     * Both sides of the comparison come from Audiobookshelf's listening-session records. That keeps the
     * decision in one clock domain: a phone clock that is fast or slow must never decide whether a remote
     * session is newer. Position magnitude is deliberately irrelevant because an intentional rewind on
     * another client is still newer activity.
     *
     * Only [ExternalSessionCheck.Ahead] is a reason to move a loaded player. A failed read, or a session of
     * BookWave's own that cannot be found among the records, resolves to the outcome that changes nothing —
     * preserving the loaded position is safer than guessing and rewinding it. The three outcomes are
     * distinguished rather than collapsed so the caller can write down *which* of them a resume happened
     * under; see [ExternalSessionCheck].
     *
     * The default keeps playback-only test fakes source-compatible, and is
     * [ExternalSessionCheck.Unavailable] because that is the truth about a fake with no server behind it.
     * Production overrides it with the Audiobookshelf listening-session endpoint.
     */
    suspend fun checkExternalSession(bookId: LibraryItemId): ExternalSessionCheck = ExternalSessionCheck.Unavailable

    /**
     * PRODUCT_SPEC PLAY-003 — imports the **server's own** session records for [bookId], and persists them.
     *
     * ### Why this is a refresh and not a second `observe`
     *
     * The rows go into the same table the local events use, so [observe] emits them without knowing where
     * they came from, and they survive going offline. Merging at read time was the alternative and it makes
     * the pane's remote half disappear the moment the network does — which is precisely when somebody is
     * most likely to be wondering where their position went.
     *
     * ### Why it is per-book and called when the pane opens
     *
     * The server has no per-book session route: this reads a page of the *account's* sessions and keeps the
     * ones for this book. Doing that on every sync would add a request to the app's cheapest and most
     * frequent call for data only one screen reads.
     *
     * ### What it deliberately does not import
     *
     * **This device's own sessions.** They come back from the server like any other, and they would
     * duplicate the `Play` and `Pause` rows the player already writes. Told apart by the per-install device
     * id the app sends when it opens a session.
     *
     * Nothing here fails in a way the user should see, for the same reason as [record]: a history pane whose
     * local half is good must not become an error because a refresh could not reach the server. A failure
     * means "nothing new to add", and the persisted rows from previous refreshes stay on screen.
     */
    suspend fun refreshServerSessions(bookId: LibraryItemId)

    suspend fun clear(bookId: LibraryItemId)

    companion object {
        /**
         * How many events a book keeps.
         *
         * Raised from forty when play, pause and the sleep timer joined the jumps: an evening of listening
         * now writes several entries an hour on its own, and a cap that the ordinary use of the app can
         * exhaust in a night is a cap that deletes the thing somebody came looking for. Per book, so one
         * restless book does not evict another's.
         */
        const val DEFAULT_LIMIT = 120
    }
}
