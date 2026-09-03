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
     * PRODUCT_SPEC PLAY-004 / SYNC-002 — the journal's write, with its unsent decision made *in the
     * statement*.
     *
     * ### The race this exists to make impossible
     *
     * The decision "does this row now say something the server has not been told" needs the row's previous
     * position, so the write that makes it is a read, a comparison and an upsert. Done in Kotlin those are
     * three steps with two gaps, and [markProgressSynced] runs from a different coroutine: a journal write
     * could read a dirty row, the upload could acknowledge and clear it, and the write that had already
     * decided would then upsert `hasUnsyncedChanges = 1` back over the acknowledgement. Nothing lowers it
     * again, and the flag was — until this rework — what the freshness check short-circuited on, so the app
     * stopped asking the server about that book entirely. Four repository-level fixes each narrowed the
     * window without closing it; `docs/risks.md` R-94 is the interleaving that survived them.
     *
     * SQLite evaluates every expression in a `SET` clause against the row as it was *before* the update, and
     * does so holding the write lock. So the read, the comparison and the write here are one indivisible
     * step: a clear either lands entirely before this statement, in which case the `CASE` sees the clean row
     * and the position it has to compare against, or entirely after it, in which case it clears whatever
     * this wrote. Neither order can invent unsent progress. `ProgressWriteInterleavingTest` holds it with a
     * latch inside the DAO.
     *
     * ### The three reasons a row becomes unsent, and the one that keeps it so
     *
     * The position moved beyond [toleranceMillis]; the book became finished; the duration became known or
     * changed. Otherwise the flag is left **exactly as it was** — which is the important clause in both
     * directions: a row already claiming unsent progress keeps claiming it however little this write moves,
     * and a row the server has just acknowledged is not re-dirtied by a duplicate record of the same
     * position.
     *
     * @return `1` when the row existed and was written, `0` when there is no row yet and the caller must
     *   insert one.
     */
    @Query(
        """
        UPDATE media_progress
        SET positionMillis = :positionMillis,
            durationMillis = CASE WHEN :durationMillis > 0 THEN :durationMillis ELSE durationMillis END,
            isFinished = CASE WHEN :isFinished <> 0 OR isFinished <> 0 THEN 1 ELSE 0 END,
            updatedAt = :updatedAt,
            hasUnsyncedChanges = CASE
                WHEN ABS(positionMillis - :positionMillis) > :toleranceMillis THEN 1
                WHEN :isFinished <> 0 AND isFinished = 0 THEN 1
                WHEN :durationMillis > 0 AND durationMillis <> :durationMillis THEN 1
                ELSE hasUnsyncedChanges
            END
        WHERE profileId = :profileId AND bookKey = :bookKey
        """,
    )
    @Suppress("LongParameterList")
    suspend fun recordPosition(
        profileId: String,
        bookKey: String,
        positionMillis: Long,
        durationMillis: Long,
        isFinished: Boolean,
        updatedAt: Long,
        toleranceMillis: Long,
    ): Int

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
