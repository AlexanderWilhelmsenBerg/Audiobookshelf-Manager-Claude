package com.example.shelfplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.shelfplayer.core.database.entity.GarminRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class GarminDao {
    @Query("SELECT * FROM garmin_records WHERE profileId = :profileId ORDER BY recordedAt DESC")
    abstract fun observe(profileId: String): Flow<List<GarminRecordEntity>>

    @Query(
        "SELECT * FROM garmin_records WHERE profileId = :profileId " +
            "AND deviceId = :deviceId AND kind = :kind ORDER BY recordedAt ASC",
    )
    abstract suspend fun records(profileId: String, deviceId: String, kind: String): List<GarminRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(rows: List<GarminRecordEntity>)

    @Query("DELETE FROM garmin_records WHERE profileId = :profileId AND deviceId = :deviceId AND kind = :kind")
    abstract suspend fun clear(profileId: String, deviceId: String, kind: String)

    @Transaction
    open suspend fun replace(profileId: String, deviceId: String, kind: String, rows: List<GarminRecordEntity>) {
        require(rows.all { it.profileId == profileId && it.deviceId == deviceId && it.kind == kind })
        clear(profileId, deviceId, kind)
        put(rows)
    }
}
