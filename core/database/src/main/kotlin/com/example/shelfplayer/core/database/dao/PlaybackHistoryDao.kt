package com.example.shelfplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.shelfplayer.core.database.entity.PlaybackHistoryEntity
import kotlinx.coroutines.flow.Flow

/** PRODUCT_SPEC PLAY-003 — the jumps a listener has made in a book, newest first. */
@Dao
interface PlaybackHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: PlaybackHistoryEntity)

    @Query(
        """
        SELECT * FROM playback_history
        WHERE profileId = :profileId AND bookKey = :bookKey
        ORDER BY at DESC, rowid DESC
        LIMIT :limit
        """,
    )
    fun observe(profileId: String, bookKey: String, limit: Int): Flow<List<PlaybackHistoryEntity>>

    /**
     * Keeps the newest [keep] rows for one book and deletes the rest.
     *
     * Per book rather than globally: a listener who seeks around one book must not lose the history of
     * another, and a global cap makes exactly that happen to whoever listens to two things at once.
     */
    @Query(
        """
        DELETE FROM playback_history
        WHERE profileId = :profileId AND bookKey = :bookKey AND entryId NOT IN (
            SELECT entryId FROM playback_history
            WHERE profileId = :profileId AND bookKey = :bookKey
            ORDER BY at DESC LIMIT :keep
        )
        """,
    )
    suspend fun prune(profileId: String, bookKey: String, keep: Int)

    @Transaction
    suspend fun record(entry: PlaybackHistoryEntity, keep: Int) {
        insert(entry)
        prune(entry.profileId, entry.bookKey, keep)
    }

    /** PLAY-004 — the mutable listening checkpoint between explicit local events. */
    @Transaction
    suspend fun recordProgress(entry: PlaybackHistoryEntity, keep: Int) {
        val latest = latestLocal(entry.profileId, entry.bookKey)
        if (latest != null && entry.at < latest.at) return
        record(
            if (latest?.reason == "ListeningProgress") entry.copy(entryId = latest.entryId) else entry,
            keep,
        )
    }

    @Query(
        """
        SELECT * FROM playback_history
        WHERE profileId = :profileId AND bookKey = :bookKey
        AND reason NOT IN ('RemoteProgress', 'RemoteFinished', 'ServerSession',
            'ServerCheckAhead', 'ServerCheckCurrent', 'ServerCheckUnavailable')
        ORDER BY at DESC, rowid DESC LIMIT 1
        """,
    )
    suspend fun latestLocal(profileId: String, bookKey: String): PlaybackHistoryEntity?

    @Query("DELETE FROM playback_history WHERE profileId = :profileId AND bookKey = :bookKey")
    suspend fun clear(profileId: String, bookKey: String)
}
