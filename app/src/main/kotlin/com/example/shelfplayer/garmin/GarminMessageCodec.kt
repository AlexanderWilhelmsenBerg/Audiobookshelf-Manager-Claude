package com.example.shelfplayer.garmin

import com.example.shelfplayer.core.common.time.AppClock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class GarminMessageCodec @Inject constructor(private val clock: AppClock) {
    private val ids = AtomicLong(0L)
    private val instance = UUID.randomUUID().toString().take(INSTANCE_ID_LENGTH)

    fun decode(value: Any?): GarminDecodeResult {
        val map = value as? Map<*, *> ?: return GarminDecodeResult.Failure("envelope_not_dictionary")
        val reason = validationError(map)
        if (reason != null) return GarminDecodeResult.Failure(reason, (map["id"] as? String)?.takeIf(::validId))
        @Suppress("UNCHECKED_CAST")
        return GarminDecodeResult.Success(
            GarminEnvelope(
                GarminBridgeConfig.PROTOCOL_MAJOR,
                requireNotNull(GarminMessageType.fromWire(map["t"] as String)),
                map["id"] as String,
                map["r"] as? String,
                integer(map["ts"]),
                map["p"] as Map<String, Any?>,
                map["s"] as? String,
                map["n"]?.let(::integer),
            ),
        )
    }

    private fun validationError(map: Map<*, *>): String? = when {
        !validId(map["id"]) -> "invalid_message_id"
        integer(map["v"]) == null -> "protocol_major_missing"
        integer(map["v"]) != GarminBridgeConfig.PROTOCOL_MAJOR.toLong() -> "unsupported_protocol_major"
        (map["t"] as? String)?.let(GarminMessageType::fromWire) == null -> "unsupported_message_type"
        !optionalId(map["r"]) -> "invalid_correlation_id"
        !positiveInteger(map["ts"]) -> "invalid_sent_timestamp"
        !validPayload(map["p"]) -> "invalid_payload"
        !optionalId(map["s"]) -> "invalid_stream"
        !optionalSequence(map["n"]) -> "invalid_sequence"
        else -> null
    }

    private fun validPayload(value: Any?): Boolean = (value as? Map<*, *>)?.let {
        it.size <= MAX_PAYLOAD_FIELDS && it.keys.all { key -> key is String }
    } ?: false

    private fun positiveInteger(value: Any?): Boolean = integer(value)?.let { it > 0L } == true
    private fun optionalId(value: Any?): Boolean = value == null || validId(value)
    private fun optionalSequence(value: Any?): Boolean = value == null || integer(value)?.let { it >= 0L } == true

    fun hello(): OutgoingGarminMessage = outgoing(
        GarminMessageType.Hello,
        mapOf(
            "majors" to listOf(1),
            "caps" to GarminCapabilities.android,
        ),
    )

    fun helloAck(replyTo: String, compatible: Boolean, stream: String): Map<String, Any> = envelope(
        GarminMessageType.HelloAck,
        mapOf("compatible" to compatible, "selected" to 1, "caps" to GarminCapabilities.android),
        replyTo = replyTo,
        stream = stream,
    )

    fun snapshot(snapshot: GarminPlaybackSnapshot, stream: String, sequence: Long): OutgoingGarminMessage =
        outgoing(GarminMessageType.Snapshot, snapshot.toWireMap(), stream, sequence)

    fun clearState(stream: String, sequence: Long): OutgoingGarminMessage =
        outgoing(GarminMessageType.ClearState, mapOf("reason" to "privacy_or_no_book"), stream, sequence)

    private fun outgoing(
        type: GarminMessageType,
        payload: Map<String, Any?>,
        stream: String? = null,
        sequence: Long? = null,
    ): OutgoingGarminMessage {
        val id = nextId()
        return OutgoingGarminMessage(id, envelope(type, payload, id = id, stream = stream, sequence = sequence))
    }

    private fun envelope(
        type: GarminMessageType,
        payload: Map<String, Any?>,
        replyTo: String? = null,
        id: String = nextId(),
        stream: String? = null,
        sequence: Long? = null,
    ): Map<String, Any> = buildMap {
        put("v", 1)
        put("t", type.wireValue)
        put("id", id)
        replyTo?.let { put("r", it) }
        put("ts", clock.now().toEpochMilli())
        stream?.let { put("s", it) }
        sequence?.let { put("n", it) }
        put("p", payload.filterValues { it != null })
    }

    private fun nextId(): String = "android-$instance-${ids.incrementAndGet()}"

    private companion object {
        const val MAX_PAYLOAD_FIELDS = 16
        const val MAX_MESSAGE_ID_LENGTH = 64
        const val INSTANCE_ID_LENGTH = 8
        fun validId(value: Any?): Boolean =
            value is String && value.isNotBlank() && value.length <= MAX_MESSAGE_ID_LENGTH
        fun integer(value: Any?): Long? = when (value) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }
    }
}

internal data class OutgoingGarminMessage(val id: String, val payload: Map<String, Any>)
