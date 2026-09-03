package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.AcknowledgedPause
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

    override suspend fun setFinished(bookId: LibraryItemId, isFinished: Boolean, position: Duration): AppResult<Unit> =
        delegate.setFinished(bookId, isFinished, position)

    /**
     * PRODUCT_SPEC SYNC-002 — passed straight through, and deliberately **without** draining the outbox
     * first.
     *
     * A drain before the read would be the wrong shape twice over. It is a network write on the path
     * between a tap and audio, which is the latency this check exists to keep down; and it would make the
     * answer meaningless — uploading this device's position moves the very number the read is about, so
     * the check would then be comparing the server against a position the drain had just put there.
     *
     * There is nothing to drain *for*, either. The decision is made against an acknowledged pause
     * ([AcknowledgedPause]): a position the server has already confirmed it holds. Anything still queued
     * by definition has not been acknowledged and therefore is not what the comparison is against.
     */
    override suspend fun checkServerPosition(bookId: LibraryItemId, baseline: AcknowledgedPause?) =
        delegate.checkServerPosition(bookId, baseline)
}
