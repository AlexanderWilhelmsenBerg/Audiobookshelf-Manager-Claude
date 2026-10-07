package com.example.shelfplayer.core.model.garmin

/** Internal durable cache document; mapped to typed display models by the device repository. */
data class GarminRecord(
    val profileId: String,
    val deviceId: String,
    val kind: String,
    val recordId: String,
    val payload: String,
    val recordedAt: Long,
)
