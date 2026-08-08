package com.example.shelfplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.shelfplayer.core.database.entity.PlaybackSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC PLAY-004 / PLAY-005 — the session outbox.
 *
 * Every read is filtered by profile in SQL, as `ProgressDao`'s are: one account's listening history can
 * never reach a screen or an upload signed by another (PRODUCT_SPEC 5.2, product priority 4). That matters
 * more here than in most tables, because the rows leave the device.
 *
 * ### The states
 *
 * `Open` — being listened to now. `Pending` — final, not yet accepted. `Synced` — accepted, kept for the
 * seven days PLAY-005 asks for. The strings live in `SessionOutboxState` rather than here; SQL sees them
 * as opaque values.
 */
@Dao
interface SessionOutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: PlaybackSessionEntity)

    @Query("SELECT * FROM playback_sessions WHERE sessionId = :sessionId")
    suspend fun find(sessionId: String): PlaybackSessionEntity?

    /**
     * The queue, oldest first.
     *
     * Oldest first because the server resolves conflicts on `updatedAt`: uploading a batch newest-first
     * would have the server accept the newest position and then decline every older one in the same
     * request, which is correct but records the drain as five declines instead of one apply.
     *
     * [SessionOutboxState.OPEN] rows are included. A session interrupted by a process death is left `Open`
     * with nobody coming back to it, and excluding it here is how it would sit in the table forever.
     */
    @Query(
        """
        SELECT * FROM playback_sessions
        WHERE profileId = :profileId AND state != :syncedState
        ORDER BY updatedAt ASC
        LIMIT :limit
        """,
    )
    suspend fun pending(profileId: String, syncedState: String, limit: Int): List<PlaybackSessionEntity>

    /**
     * Marks a session the server accepted.
     *
     * `wasProgressApplied` is stored rather than acted on. An accepted session whose position the server
     * declined is *done* — it held something newer — and retrying it would be the app fighting the conflict
     * rule PLAY-004 asks it to respect.
     */
    @Query(
        """
        UPDATE playback_sessions
        SET state = :syncedState, syncedAt = :syncedAt, wasProgressApplied = :wasProgressApplied,
            lastErrorCode = NULL
        WHERE sessionId = :sessionId
        """,
    )
    suspend fun markSynced(sessionId: String, syncedState: String, syncedAt: Long, wasProgressApplied: Boolean): Int

    /**
     * Records an attempt that did not land.
     *
     * The row stays where it is. There is no attempt ceiling on purpose: an outbox that gives up on a
     * session has lost the listening in it, and product priority 2 does not allow that. [attempts] is a
     * diagnostic, and a number that keeps climbing is the honest report of a server that keeps refusing.
     */
    @Query(
        """
        UPDATE playback_sessions
        SET attempts = attempts + 1, lastErrorCode = :errorCode
        WHERE sessionId IN (:sessionIds)
        """,
    )
    suspend fun markAttempted(sessionIds: List<String>, errorCode: String?): Int

    /** Closes a session left `Open` by a process death, so the drain can finalize it. */
    @Query(
        """
        UPDATE playback_sessions
        SET state = :pendingState
        WHERE profileId = :profileId AND state = :openState
        """,
    )
    suspend fun closeOpen(profileId: String, openState: String, pendingState: String): Int

    /**
     * PRODUCT_SPEC PLAY-005 — "synced outbox records are retained for seven days, then compacted".
     *
     * Only `Synced` rows, and only by [syncedAt]. Compacting on `updatedAt` would delete a session recorded
     * eight days ago that has still never reached the server — which is precisely the session worth keeping.
     */
    @Query(
        """
        DELETE FROM playback_sessions
        WHERE state = :syncedState AND syncedAt IS NOT NULL AND syncedAt < :before
        """,
    )
    suspend fun compact(syncedState: String, before: Long): Int

    @Query("SELECT COUNT(*) FROM playback_sessions WHERE profileId = :profileId")
    fun observeCount(profileId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM playback_sessions WHERE profileId = :profileId AND state = :state")
    fun observeCountIn(profileId: String, state: String): Flow<Int>

    /** PRODUCT_SPEC PLAY-004 — accepted sessions whose position the server held something newer than. */
    @Query(
        """
        SELECT COUNT(*) FROM playback_sessions
        WHERE profileId = :profileId AND wasProgressApplied = 0
        """,
    )
    fun observeProgressDeclined(profileId: String): Flow<Int>

    /** The most recently accepted row, which is what "last synced" on the diagnostics screen means. */
    @Query(
        """
        SELECT * FROM playback_sessions
        WHERE profileId = :profileId AND syncedAt IS NOT NULL
        ORDER BY syncedAt DESC
        LIMIT 1
        """,
    )
    fun observeLastSynced(profileId: String): Flow<PlaybackSessionEntity?>

    /**
     * The most recent failure that is still queued.
     *
     * Ordered by `updatedAt` rather than by attempt count: the question the screen answers is "is the thing
     * I just listened to stuck", not "which row has failed most often since install".
     */
    @Query(
        """
        SELECT * FROM playback_sessions
        WHERE profileId = :profileId AND state != :syncedState AND lastErrorCode IS NOT NULL
        ORDER BY updatedAt DESC
        LIMIT 1
        """,
    )
    fun observeLastFailure(profileId: String, syncedState: String): Flow<PlaybackSessionEntity?>
}
