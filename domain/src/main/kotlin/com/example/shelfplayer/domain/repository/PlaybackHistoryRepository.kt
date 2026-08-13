package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.playback.PlaybackHistoryEntry
import com.example.shelfplayer.core.model.playback.PlaybackJump
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-003 — the jumps a listener has made in a book.
 *
 * A seek is the one playback action with no undo: the position it replaced is gone the moment it lands, and
 * "somewhere around eleven hours" is not a position on a thirty-hour book. This is what makes it reversible.
 *
 * Nothing here fails in a way the user should see. Recording a jump is a side effect of a jump that has
 * already happened, and the jump must not be undone because a row could not be written (product priority 1).
 */
interface PlaybackHistoryRepository {

    /** This book's jumps, newest first. Empty for a book nobody has moved around in. */
    fun observe(bookId: LibraryItemId, limit: Int = DEFAULT_LIMIT): Flow<List<PlaybackHistoryEntry>>

    /**
     * Writes one jump down.
     *
     * @param from `null` only for [PlaybackJump.Resume]. Everything else has a position it left.
     */
    suspend fun record(bookId: LibraryItemId, jump: PlaybackJump, from: Duration?, to: Duration)

    suspend fun clear(bookId: LibraryItemId)

    companion object {
        /**
         * How many jumps a book keeps.
         *
         * Enough to cover a session of hunting for a half-remembered passage, few enough that the list is
         * still readable. Per book, so one restless book does not evict another's.
         */
        const val DEFAULT_LIMIT = 40
    }
}
