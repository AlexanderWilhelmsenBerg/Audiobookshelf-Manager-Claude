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
     * ### It compares the **position**, not a timestamp, and that is a correction
     *
     * The first version cleared rows whose `updatedAt` was at or before the moment the upload was built,
     * so that a position recorded while the request was in flight kept its flag. The intent was right and
     * the mechanism raced: `recordPosition` and the session sync each stamp their own `clock.now()` on
     * independent coroutines, so the sync could easily take its stamp *before* the launched
     * `recordPosition` wrote its row — and then the clear matched nothing and the flag stood forever.
     *
     * Which is not theoretical. Three device logs contain no `GET /api/me/progress/{id}` request at all:
     * the freshness check was short-circuiting on a flag that no upload could clear, so it never once
     * asked the server. `docs/risks.md` R-92.
     *
     * Comparing positions says the true thing directly — *the server is holding this position, so this row
     * has nothing unsent* — and it cannot race, because a listener who kept playing has a row whose
     * position has moved well past the uploaded one and correctly keeps its flag. The tolerance is there
     * because `recordPosition` and the sync read the player independently and land a few milliseconds
     * apart; two consecutive syncs of a *paused* player logged `22256204ms` and `22256207ms`.
     *
     * @return how many rows were cleared, so a caller can log the count rather than assume one.
     */
    @Query(
        """
        UPDATE media_progress
        SET hasUnsyncedChanges = 0
        WHERE profileId = :profileId AND bookKey = :bookKey
          AND ABS(positionMillis - :positionMillis) <= :toleranceMillis
        """,
    )
    suspend fun markProgressSynced(
        profileId: String,
        bookKey: String,
        positionMillis: Long,
        toleranceMillis: Long,
    ): Int

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
