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

    /**
     * PRODUCT_SPEC PLAY-004 — one book's row, for the write path that runs every few seconds.
     *
     * [findProgressFor] would answer the same question by reading the profile's whole listening history
     * and filtering it in memory. That is fine once per sync and wrong on a timer: a listener with a
     * thousand books would deserialize a thousand rows every five seconds for the life of a session.
     */
    @Query("SELECT * FROM media_progress WHERE profileId = :profileId AND bookKey = :bookKey")
    suspend fun findProgress(profileId: String, bookKey: String): MediaProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: List<MediaProgressEntity>)

    /**
     * PRODUCT_SPEC PLAY-004 / SYNC-002 — the position for this book has reached the server, so stop
     * claiming otherwise.
     *
     * ### The write that was missing
     *
     * Every `recordPosition` sets `hasUnsyncedChanges = 1` and, before this, nothing ever set it back.
     * `DefaultLibraryRepository.writeProgress` refuses to overwrite a flagged row — correctly, since that
     * would lose a position — but that made the flag self-perpetuating: only a server-sourced write could
     * clear it, and a flagged row was exactly what a server-sourced write skipped. A full library refresh
     * cleared it as a side effect, which made the whole thing intermittent rather than obviously broken.
     *
     * It became visible when the freshness check before a Play started reading the flag: after any
     * playback in this app, the check short-circuited to "nothing newer" and never asked the server, so a
     * position moved on another client was never picked up.
     *
     * ### `updatedAt <= :uploadedAt` is load-bearing
     *
     * The upload carries the position as it was when the request was built. If the listener kept playing
     * while it was in flight, the stored row is newer than what the server now holds and is still unsynced
     * — clearing it would be a lie that lets the next account sync overwrite it. Comparing against the
     * uploaded moment keeps the flag exactly when it is still true.
     *
     * @return how many rows were cleared, so a caller can log the count rather than assume one.
     */
    @Query(
        """
        UPDATE media_progress
        SET hasUnsyncedChanges = 0
        WHERE profileId = :profileId AND bookKey = :bookKey AND updatedAt <= :uploadedAt
        """,
    )
    suspend fun markProgressSynced(profileId: String, bookKey: String, uploadedAt: Long): Int

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
