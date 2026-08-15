package com.example.shelfplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.shelfplayer.core.database.entity.MetadataDraftEntity
import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC MGR-001 / 5.2 — one unsaved metadata edit per book, per profile.
 *
 * Scoped by profile in SQL, like every other per-account table here: two household members editing the
 * same book keep their own drafts, and neither can see the other's half-written words.
 */
@Dao
interface MetadataDraftDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: MetadataDraftEntity)

    @Query("SELECT * FROM metadata_draft WHERE profileId = :profileId AND bookKey = :bookKey")
    fun observe(profileId: String, bookKey: String): Flow<MetadataDraftEntity?>

    @Query("DELETE FROM metadata_draft WHERE profileId = :profileId AND bookKey = :bookKey")
    suspend fun delete(profileId: String, bookKey: String)
}
