package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.SessionSyncRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * Keeps BookWave's remembered book local while refreshing that book's server progress before it opens.
 *
 * The session outbox already carries honest timestamps and Audiobookshelf already resolves competing
 * progress by those timestamps. Draining it first therefore settles the only conflict that matters here:
 * the same book may have moved on this phone or on another client since BookWave last opened it. We do not
 * inspect account-wide listening history and we never let another client's different book replace the book
 * this device remembers.
 *
 * A failed drain is deliberately not a failed Play. The queued rows remain durable, and the normal session
 * open still gets its chance to reach the server or fall back to a downloaded copy.
 */
@Singleton
class RefreshingPlaybackRepository @Inject constructor(
    private val delegate: DefaultPlaybackRepository,
    private val sessionSync: SessionSyncRepository,
) : PlaybackRepository {
    override suspend fun openSession(bookId: LibraryItemId): AppResult<PlaybackSession> {
        sessionSync.drainOutbox()
        return delegate.openSession(bookId)
    }

    override suspend fun recordPosition(
        bookId: LibraryItemId,
        position: Duration,
        duration: Duration,
        owner: ProfileId?,
    ): AppResult<Unit> = delegate.recordPosition(bookId, position, duration, owner)

    override suspend fun setFinished(bookId: LibraryItemId, isFinished: Boolean, position: Duration): AppResult<Unit> {
        return delegate.setFinished(bookId, isFinished, position)
    }
}
