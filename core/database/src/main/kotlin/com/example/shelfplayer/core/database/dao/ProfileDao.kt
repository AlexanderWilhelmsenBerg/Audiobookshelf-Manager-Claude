package com.example.shelfplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY displayName COLLATE NOCASE ASC")
    fun observeProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE profileId = :profileId")
    fun observeProfile(profileId: String): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE profileId = :profileId")
    suspend fun findProfile(profileId: String): ProfileEntity?

    @Query("SELECT * FROM servers WHERE serverId = :serverId")
    suspend fun findServer(serverId: String): ServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertServer(server: ServerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: ProfileEntity)

    /**
     * PRODUCT_SPEC AUTH-002 — removing one profile must not remove another profile's data.
     *
     * The cascade deletes only rows keyed by this `profileId` (progress, sync state). Books and
     * libraries belong to the server, and other profiles on the same server keep them.
     */
    @Query("DELETE FROM profiles WHERE profileId = :profileId")
    suspend fun deleteProfile(profileId: String)
}
