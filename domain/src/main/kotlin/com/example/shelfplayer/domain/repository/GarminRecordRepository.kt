package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.garmin.GarminRecord
import kotlinx.coroutines.flow.Flow

/** SET-002: durable profile/device-scoped outbox, inventory and original watch events. */
interface GarminRecordRepository {
    fun observe(profileId: String): Flow<List<GarminRecord>>
    suspend fun records(profileId: String, deviceId: String, kind: String): List<GarminRecord>
    suspend fun put(rows: List<GarminRecord>)
    suspend fun replace(profileId: String, deviceId: String, kind: String, rows: List<GarminRecord>)
}
