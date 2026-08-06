package com.example.shelfplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.shelfplayer.core.database.entity.MediaProgressEntity
import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC PLAY-004 — progress is per profile, and every read is filtered by profile in SQL.
 *
 * Filtering in the query rather than in a mapper is what makes PRODUCT_SPEC 5.2 structural: a screen
 * cannot render another account's position because the rows never leave the database.
 */
@Dao
interface ProgressDao {
    @Query("SELECT * FROM media_progress WHERE profileId = :profileId")
    fun observeProgressFor(profileId: String): Flow<List<MediaProgressEntity>>

    @Query("SELECT * FROM media_progress WHERE profileId = :profileId")
    suspend fun findProgressFor(profileId: String): List<MediaProgressEntity>

    @Query("SELECT * FROM media_progress WHERE profileId = :profileId AND bookKey = :bookKey")
    fun observeProgress(profileId: String, bookKey: String): Flow<MediaProgressEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: List<MediaProgressEntity>)

    /**
     * PRODUCT_SPEC DL-006 — cleanup must never remove a book with unsynced progress, so the count is
     * a first-class query rather than something a caller filters in memory.
     */
    @Query(
        """
        SELECT COUNT(*) FROM media_progress
        WHERE profileId = :profileId AND hasUnsyncedChanges = 1
        """,
    )
    suspend fun countUnsynced(profileId: String): Int

    /** PRODUCT_SPEC SET-002 (Privacy/diagnostics) — how much of this profile's listening is on device. */
    @Query("SELECT COUNT(*) FROM media_progress WHERE profileId = :profileId")
    fun observeProgressCount(profileId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM media_progress WHERE profileId = :profileId AND hasUnsyncedChanges = 1")
    fun observeUnsyncedCount(profileId: String): Flow<Int>
}
