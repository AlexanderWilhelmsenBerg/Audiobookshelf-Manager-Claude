package com.example.shelfplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.shelfplayer.core.database.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC 11.1 / 5.2 — a book's bookmarks, scoped to the profile that made them.
 *
 * Filtering by profile in SQL rather than in a mapper is what makes 5.2 structural: a screen cannot render
 * another account's notes, because the rows never leave the database.
 *
 * Every read excludes [BookmarkEntity.isPendingDelete]. A bookmark the listener has deleted is gone from
 * their point of view the moment they delete it, whether or not the server has been told — and a row that
 * reappeared in the list while an upload retried would be the app arguing with them.
 */
@Dao
interface BookmarkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmarks: List<BookmarkEntity>)

    /** One book's bookmarks, earliest position first — the order they occur in the book. */
    @Query(
        """
        SELECT * FROM bookmarks
        WHERE profileId = :profileId AND bookKey = :bookKey AND isPendingDelete = 0
        ORDER BY atSeconds ASC
        """,
    )
    fun observe(profileId: String, bookKey: String): Flow<List<BookmarkEntity>>

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE profileId = :profileId AND bookKey = :bookKey AND atSeconds = :atSeconds
        """,
    )
    suspend fun find(profileId: String, bookKey: String, atSeconds: Long): BookmarkEntity?

    /**
     * Every row this profile holds, including the ones pending deletion.
     *
     * The exception to the rule above, and the reason it exists: this is what the refresh reads to decide
     * what the server's array should not overwrite, and a pending delete is precisely the case it has to
     * know about.
     */
    @Query("SELECT * FROM bookmarks WHERE profileId = :profileId")
    suspend fun findAllFor(profileId: String): List<BookmarkEntity>

    @Query("DELETE FROM bookmarks WHERE bookmarkId = :bookmarkId")
    suspend fun deleteById(bookmarkId: String)

    /** PRODUCT_SPEC SET-002 (Privacy/diagnostics) — how many of this profile's notes are on the device. */
    @Query("SELECT COUNT(*) FROM bookmarks WHERE profileId = :profileId AND isPendingDelete = 0")
    fun observeCount(profileId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM bookmarks WHERE profileId = :profileId AND hasUnsyncedChanges = 1")
    fun observeUnsyncedCount(profileId: String): Flow<Int>

    /**
     * Replaces this profile's cached bookmarks with the server's, keeping what the server has not seen.
     *
     * One transaction, because the two halves are one decision: a crash between them would leave a profile
     * with a partially-applied refresh, which for this table means silently losing notes.
     *
     * [keep] is the set of primary keys the caller has determined the server's array must not speak for —
     * unsynced creates and pending deletes. Everything else this profile holds is replaced by [remote].
     */
    @Transaction
    suspend fun replaceRemote(profileId: String, remote: List<BookmarkEntity>, keep: Set<String>) {
        findAllFor(profileId)
            .filterNot { it.bookmarkId in keep }
            .forEach { stale -> deleteById(stale.bookmarkId) }
        upsert(remote.filterNot { it.bookmarkId in keep })
    }
}
