package com.example.shelfplayer.data.downloads

import com.example.shelfplayer.core.database.dao.GarminDao
import com.example.shelfplayer.core.database.entity.GarminRecordEntity
import com.example.shelfplayer.core.model.garmin.GarminRecord
import com.example.shelfplayer.domain.repository.GarminRecordRepository
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Room owns all visible inventory, sync times, listening events and pending requests. */
@Singleton
class GarminDeviceStore @Inject constructor(private val dao: GarminDao) : GarminRecordRepository {
    override fun observe(profileId: String) = dao.observe(profileId).map { rows -> rows.map { it.model() } }
    override suspend fun records(profileId: String, deviceId: String, kind: String) =
        dao.records(profileId, deviceId, kind).map { it.model() }
    override suspend fun put(rows: List<GarminRecord>) = dao.put(rows.map { it.entity() })
    override suspend fun replace(profileId: String, deviceId: String, kind: String, rows: List<GarminRecord>) =
        dao.replace(profileId, deviceId, kind, rows.map { it.entity() })
    private fun GarminRecordEntity.model() = GarminRecord(profileId, deviceId, kind, recordId, payload, recordedAt)
    private fun GarminRecord.entity() = GarminRecordEntity(profileId, deviceId, kind, recordId, payload, recordedAt)
}
