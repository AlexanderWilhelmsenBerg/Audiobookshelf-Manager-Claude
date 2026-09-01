package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
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
     * Returns true only when another device has touched this book after the currently loaded server session.
     *
     * This is deliberately a freshness question, not a position comparison. A listener may intentionally
     * rewind on another client; if that session is newer its lower position is still the correct one. If the
     * server has not yet updated this device's current session, the current session cannot be established as
     * a baseline and this returns false — preserving the player's local position is safer than rewinding it
     * from an ambiguous server read.
     */
    suspend fun hasNewerExternalSession(bookId: LibraryItemId, currentSessionId: String): Boolean

    /**
     * PRODUCT_SPEC PLAY-003 — imports the **server's own** session records for [bookId], and persists them.
     */
    suspend fun refreshServerSessions(bookId: LibraryItemId)

    suspend fun clear(bookId: LibraryItemId)

    companion object {
        const val DEFAULT_LIMIT = 120
    }
}
