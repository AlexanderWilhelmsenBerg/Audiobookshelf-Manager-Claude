package com.example.shelfplayer.garmin

import com.example.shelfplayer.core.common.time.AppClock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class GarminMessageCodec @Inject constructor(
    private val clock: AppClock,
) {
    private val ids = AtomicLong(0L)

    fun decode(value: Any?): GarminDecodeResult {
        val map = value as? Map<*, *> ?: return GarminDecodeResult.Failure("envelope_not_dictionary")
        val messageId = map["id"] as? String
        val major = (map["v"] as? Number)?.toInt()
            ?: return GarminDecodeResult.Failure("protocol_major_missing", messageId)
        if (major != GarminBridgeConfig.PROTOCOL_MAJOR) {
            return GarminDecodeResult.Failure("unsupported_protocol_major", messageId)
        }

        val typeValue = map["t"] as? String
            ?: return GarminDecodeResult.Failure("message_type_missing", messageId)
        val type = GarminMessageType.fromWire(typeValue)
            ?: return GarminDecodeResult.Failure("unsupported_message_type", messageId)

        val id = messageId?.takeIf { it.isNotBlank() && it.length <= MAX_MESSAGE_ID_LENGTH }
            ?: return GarminDecodeResult.Failure("invalid_message_id", messageId)

        val replyTo = map["r"] as? String
        if (replyTo != null && (replyTo.isBlank() || replyTo.length > MAX_MESSAGE_ID_LENGTH)) {
            return GarminDecodeResult.Failure("invalid_correlation_id", id)
        }

        val rawTimestamp = map["ts"]
        val sentAt = when (rawTimestamp) {
            null -> null
            is Number -> rawTimestamp.toLong()
            else -> return GarminDecodeResult.Failure("invalid_sent_timestamp", id)
        }

        val rawPayload = map["p"]
        val payload = when (rawPayload) {
            null -> emptyMap()
            is Map<*, *> -> rawPayload.entries.associate { (key, item) -> key.toString() to item }
            else -> return GarminDecodeResult.Failure("invalid_payload", id)
        }

        return GarminDecodeResult.Success(
            GarminEnvelope(
                protocolMajor = major,
                type = type,
                id = id,
                replyTo = replyTo,
                sentAtEpochMs = sentAt,
                payload = payload,
            ),
        )
    }

    fun helloAck(
        replyTo: String?,
        compatible: Boolean,
    ): Map<String, Any> = envelope(
        type = GarminMessageType.HelloAck,
        replyTo = replyTo,
        payload = buildMap {
            put("compatible", compatible)
            if (compatible) {
                put("selected", GarminBridgeConfig.PROTOCOL_MAJOR)
                put("caps", GarminCapabilities.android)
            }
        },
    )

    fun snapshot(snapshot: GarminPlaybackSnapshot): OutgoingGarminMessage = outgoing(
        type = GarminMessageType.Snapshot,
        payload = snapshot.toWireMap(),
    )

    fun clearState(): OutgoingGarminMessage = outgoing(
        type = GarminMessageType.ClearState,
        payload = mapOf("reason" to "privacy"),
    )

    fun error(
        replyTo: String?,
        reason: String,
    ): Map<String, Any> = envelope(
        type = GarminMessageType.Error,
        replyTo = replyTo,
        payload = mapOf("reason" to reason.take(MAX_REASON_LENGTH)),
    )

    private fun outgoing(
        type: GarminMessageType,
        payload: Map<String, Any?>,
    ): OutgoingGarminMessage {
        val id = nextId(type)
        return OutgoingGarminMessage(id, envelope(type, payload, id = id))
    }

    private fun envelope(
        type: GarminMessageType,
        payload: Map<String, Any?>,
        replyTo: String? = null,
        id: String = nextId(type),
    ): Map<String, Any> = buildMap {
        put("v", GarminBridgeConfig.PROTOCOL_MAJOR)
        put("t", type.wireValue)
        put("id", id)
        replyTo?.let { put("r", it) }
        put("ts", clock.now().toEpochMilli())
        if (payload.isNotEmpty()) {
            @Suppress("UNCHECKED_CAST")
            put("p", payload.filterValues { it != null } as Map<String, Any>)
        } else {
            put("p", emptyMap<String, Any>())
        }
    }

    private fun nextId(type: GarminMessageType): String =
        "android-" + type.wireValue + "-" + clock.now().toEpochMilli() + "-" + ids.incrementAndGet()

    private companion object {
        const val MAX_MESSAGE_ID_LENGTH = 64
        const val MAX_REASON_LENGTH = 64
    }
}

internal data class OutgoingGarminMessage(
    val id: String,
    val payload: Map<String, Any>,
)
