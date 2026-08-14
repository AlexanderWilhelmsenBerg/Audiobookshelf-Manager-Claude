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
        ORDER BY at DESC
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

    @Query("DELETE FROM playback_history WHERE profileId = :profileId AND bookKey = :bookKey")
    suspend fun clear(profileId: String, bookKey: String)
}
