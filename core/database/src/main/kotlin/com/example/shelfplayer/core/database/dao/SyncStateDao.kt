package com.example.shelfplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.shelfplayer.core.database.entity.SyncStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE profileId = :profileId")
    fun observeSyncState(profileId: String): Flow<SyncStateEntity?>

    @Query("SELECT * FROM sync_state WHERE profileId = :profileId")
    suspend fun findSyncState(profileId: String): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncState(state: SyncStateEntity)
}
