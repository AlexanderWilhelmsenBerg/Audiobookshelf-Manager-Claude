package com.example.shelfplayer.garmin

internal object GarminBridgeConfig {
    const val PROTOCOL_MAJOR = 1
    const val COMPANION_APPLICATION_ID = "6f5b4fa4a2db4d42a9d41ee67df34d11"
}

internal enum class GarminMessageType(val wireValue: String) {
    Hello("hello"),
    HelloAck("hello_ack"),
    Snapshot("snapshot"),
    SnapshotAck("snapshot_ack"),
    StateRequest("state_request"),
    ClearState("clear_state"),
    ClearAck("clear_ack"),
    Error("error"),
    ;

    companion object {
        fun fromWire(value: String): GarminMessageType? = entries.firstOrNull { it.wireValue == value }
    }
}

internal object GarminCapabilities {
    const val Snapshot = "snapshot"
    const val SnapshotAck = "snapshot_ack"
    const val StateRequest = "state_request"
    const val ClearState = "clear_state"

    val android = listOf(Snapshot, SnapshotAck, StateRequest, ClearState)
}

internal data class GarminEnvelope(
    val protocolMajor: Int,
    val type: GarminMessageType,
    val id: String,
    val replyTo: String?,
    val sentAtEpochMs: Long?,
    val payload: Map<String, Any?>,
)

internal sealed interface GarminDecodeResult {
    data class Success(val envelope: GarminEnvelope) : GarminDecodeResult

    data class Failure(
        val reason: String,
        val messageId: String? = null,
    ) : GarminDecodeResult
}

internal data class GarminPlaybackSnapshot(
    val protocolVersion: Int,
    val profileId: String,
    val bookId: String,
    val title: String,
    val author: String?,
    val chapterTitle: String?,
    val positionMs: Long,
    val durationMs: Long?,
    val updatedAt: Long,
    val playing: Boolean,
    val source: String = "PHONE",
) {
    fun toWireMap(): Map<String, Any?> = buildMap {
        put("protocolVersion", protocolVersion)
        put("profileId", profileId)
        put("bookId", bookId)
        put("title", title)
        author?.let { put("author", it) }
        chapterTitle?.let { put("chapterTitle", it) }
        put("positionMs", positionMs)
        durationMs?.let { put("durationMs", it) }
        put("updatedAt", updatedAt)
        put("playing", playing)
        put("source", source)
    }
}
