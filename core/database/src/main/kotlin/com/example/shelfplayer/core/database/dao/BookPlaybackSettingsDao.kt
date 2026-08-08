package com.example.shelfplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.shelfplayer.core.database.entity.BookPlaybackSettingsEntity
import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC PLAY-007 — the per-book speed override, filtered by profile in SQL.
 *
 * Same rule as every other per-profile table: the row never leaves the database for the wrong account
 * (PRODUCT_SPEC 5.2). A speed is a small thing to leak, but the query that leaks it is the same query
 * shape that would leak a position.
 */
@Dao
interface BookPlaybackSettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: BookPlaybackSettingsEntity)

    @Query("SELECT * FROM book_playback_settings WHERE profileId = :profileId AND bookKey = :bookKey")
    fun observe(profileId: String, bookKey: String): Flow<BookPlaybackSettingsEntity?>

    @Query("SELECT * FROM book_playback_settings WHERE profileId = :profileId AND bookKey = :bookKey")
    suspend fun find(profileId: String, bookKey: String): BookPlaybackSettingsEntity?

    /**
     * Clears the override, so the book follows the profile default again.
     *
     * A delete rather than storing the default value. "This book has no override" and "this book is
     * deliberately set to 1.0×" look identical if the second is stored as a row — and they are different
     * the moment the profile default changes.
     */
    @Query("DELETE FROM book_playback_settings WHERE profileId = :profileId AND bookKey = :bookKey")
    suspend fun clear(profileId: String, bookKey: String): Int

    /** PRODUCT_SPEC SET-002 — how many books this profile has given their own speed. */
    @Query("SELECT COUNT(*) FROM book_playback_settings WHERE profileId = :profileId")
    fun observeCount(profileId: String): Flow<Int>
}
