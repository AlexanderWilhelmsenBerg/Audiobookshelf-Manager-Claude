package com.example.shelfplayer.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** SET-002 / SYNC-001: profile + device scoped cache, command outbox and idempotent watch events. */
@Entity(
    tableName = "garmin_records",
    primaryKeys = ["profileId", "deviceId", "kind", "recordId"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["profileId"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId")],
)
data class GarminRecordEntity(
    val profileId: String,
    val deviceId: String,
    val kind: String,
    val recordId: String,
    val payload: String,
    val recordedAt: Long,
)
