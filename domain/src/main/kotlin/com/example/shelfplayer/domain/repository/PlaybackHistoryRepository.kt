package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.playback.ListeningSession
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
     * PRODUCT_SPEC PLAY-003 — imports the **server's own** session records for [bookId], and persists them.
     *
     * The server has no per-book session route: this reads a page of the account's sessions and keeps the
     * ones for this book. This device's own sessions are not imported because the local player already writes
     * their transport events.
     */
    suspend fun refreshServerSessions(bookId: LibraryItemId)

    /**
     * The server session that was active most recently for the active profile, or `null` when it cannot be
     * read or no session contains actual listening.
     *
     * Unlike [refreshServerSessions], this deliberately includes this device. Resume selection is asking
     * what the account last listened to anywhere, not which rows should be imported as *remote* history.
     * [ListeningSession.updatedAt] is used rather than its start time, because an older long-running session
     * can have been active after a newer short one began.
     *
     * A default keeps lightweight fakes source-compatible; a fake that does not model the network truth has
     * no server candidate, which is the same safe fallback as being offline.
     */
    suspend fun latestServerSession(): ListeningSession? = null

    suspend fun clear(bookId: LibraryItemId)

    companion object {
        const val DEFAULT_LIMIT = 120
    }
}
