package com.example.shelfplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.shelfplayer.core.database.entity.SleepTimerSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC PLAY-008 / SET-002 — the sleep-timer history, per profile.
 *
 * Every read is filtered by profile in SQL, for the same reason `ProgressDao`'s are: a screen cannot
 * render another account's night-time habits because the rows never leave the database
 * (PRODUCT_SPEC 5.2).
 */
@Dao
interface SleepTimerDao {
    /** Newest first, which is the only order a history is read in. */
    @Query(
        """
        SELECT * FROM sleep_timer_sessions
        WHERE profileId = :profileId
        ORDER BY startedAt DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(profileId: String, limit: Int): Flow<List<SleepTimerSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SleepTimerSessionEntity)

    @Query("SELECT * FROM sleep_timer_sessions WHERE sessionId = :sessionId")
    suspend fun find(sessionId: String): SleepTimerSessionEntity?

    /**
     * Closes a session that was left running.
     *
     * Called at startup. A timer running when the process died has no `endedAt`, and leaving it open
     * would make every later read show a timer that is not running — so it is closed as
     * `Abandoned`, which is the truth: the app stopped, and nobody knows whether the book did.
     */
    @Query(
        """
        UPDATE sleep_timer_sessions
        SET endedAt = :endedAt, outcome = :outcome
        WHERE endedAt IS NULL
        """,
    )
    suspend fun closeOrphaned(endedAt: Long, outcome: String): Int

    /**
     * PRODUCT_SPEC 13.4 — the history is bounded rather than growing for the life of the install.
     *
     * A row per night is small, and "small times forever" is still a table nobody pruned. Keeping the
     * most recent [keep] rows per profile is enough to answer the question the log exists for.
     */
    @Query(
        """
        DELETE FROM sleep_timer_sessions
        WHERE profileId = :profileId AND sessionId NOT IN (
            SELECT sessionId FROM sleep_timer_sessions
            WHERE profileId = :profileId
            ORDER BY startedAt DESC
            LIMIT :keep
        )
        """,
    )
    suspend fun prune(profileId: String, keep: Int): Int
}
