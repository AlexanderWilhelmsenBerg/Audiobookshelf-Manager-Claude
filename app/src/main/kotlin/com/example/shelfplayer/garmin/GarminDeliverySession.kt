package com.example.shelfplayer.garmin

import com.example.shelfplayer.core.common.time.AppClock
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Phase 2 delivery only. Owns no playback, credentials, server state or progress reconciliation. */
internal class GarminDeliverySession @Inject constructor(
    private val codec: GarminMessageCodec,
    private val policy: GarminSnapshotSendPolicy,
    private val clock: AppClock,
) {
    private var device: GarminDeviceRef? = null
    private var snapshot: GarminPlaybackSnapshot? = null
    private var generation: Long? = null
    private var stream: String? = null
    private var sequence = 0L
    private var clearRequired = true
    private var pending: Delivery? = null
    private var hello: OutgoingGarminMessage? = null
    private var helloAttempts = 0
    private var helloAt = Duration.ZERO
    private var incompatible = false
    var state: GarminBridgeState = GarminBridgeState.SdkUnavailable("NOT_STARTED")
        private set

    fun connection(next: GarminSdkState, send: (Map<String, Any>) -> Boolean) {
        val available = (next as? GarminSdkState.AppAvailable)?.device
        if (available != device) {
            resetDelivery()
            device = available
            state = next.toBridgeState()
            if (available != null) {
                hello = codec.hello()
                sendHello(send)
            }
        } else if (available == null) {
            state = next.toBridgeState()
        }
    }

    fun select(value: GarminPlaybackSnapshot?, profileGeneration: Long, send: (Map<String, Any>) -> Boolean) {
        if (generation != profileGeneration || (value == null && snapshot != null)) {
            pending = null
            clearRequired = true
            policy.reset()
        }
        generation = profileGeneration
        snapshot = value
        flush(send)
    }

    fun receive(raw: Any, send: (Map<String, Any>) -> Boolean) {
        if (device == null) return
        val decoded = codec.decode(raw) as? GarminDecodeResult.Success ?: return
        val message = decoded.envelope
        when (message.type) {
            GarminMessageType.Hello -> negotiate(message, send)

            GarminMessageType.SnapshotAck, GarminMessageType.ClearAck -> acknowledge(message, send)

            GarminMessageType.StateRequest -> {
                if (message.stream == stream && stream != null && !incompatible) {
                    if (pending?.attempts == MAX_ATTEMPTS) pending = null
                    policy.reset()
                    if (snapshot == null) clearRequired = true
                    flush(send)
                }
            }

            GarminMessageType.HelloAck, GarminMessageType.Snapshot,
            GarminMessageType.ClearState, GarminMessageType.Error,
            -> Unit // No command, server request or arbitrary remote error is executed or logged.
        }
    }

    fun tick(send: (Map<String, Any>) -> Boolean) {
        if (device == null || incompatible) return
        if (stream == null) {
            if (helloAttempts < MAX_ATTEMPTS && clock.elapsed() - helloAt >= ACK_TIMEOUT) sendHello(send)
            return
        }
        val delivery = pending ?: return flush(send)
        if (clock.elapsed() - delivery.sentAt < ACK_TIMEOUT) return
        if (delivery.attempts >= MAX_ATTEMPTS) {
            // Retain only this bounded message; a fresh watch hello/state request can recover.
            state = GarminBridgeState.AppUnavailable(requireNotNull(device).name, "ACK_TIMEOUT")
        } else {
            send(delivery.message.payload)
            pending = delivery.copy(attempts = delivery.attempts + 1, sentAt = clock.elapsed())
        }
    }

    fun stop() {
        device = null
        snapshot = null
        generation = null
        resetDelivery()
        state = GarminBridgeState.SdkUnavailable("SDK_STOPPED")
    }

    private fun negotiate(message: GarminEnvelope, send: (Map<String, Any>) -> Boolean) {
        val majors = message.payload["majors"] as? List<*>
        val capabilities = message.payload["caps"] as? List<*>
        val compatible = majors?.contains(1) == true && capabilities?.contains(GarminCapabilities.ORDERED_STATE) == true
        // A hello is a new watch nonce. Old queued hello_ack packets cannot rebind a restarted watch.
        if (message.id != stream || incompatible) {
            pending = null
            sequence = 0L
            clearRequired = true
            policy.reset()
            stream = message.id
        }
        incompatible = !compatible
        send(codec.helloAck(message.id, compatible, message.id))
        if (!compatible) {
            state = GarminBridgeState.ProtocolIncompatible(requireNotNull(device).name)
            return
        }
        state = GarminBridgeState.AppAvailable(requireNotNull(device).name)
        flush(send)
    }

    private fun acknowledge(message: GarminEnvelope, send: (Map<String, Any>) -> Boolean) {
        val delivery = pending ?: return
        val expected = if (delivery.snapshot == null) GarminMessageType.ClearAck else GarminMessageType.SnapshotAck
        if (message.type != expected || !matchesDelivery(message, delivery.message.id) ||
            message.payload["accepted"] !is Boolean
        ) {
            return
        }
        if (message.payload["accepted"] != true) {
            state = GarminBridgeState.AppUnavailable(requireNotNull(device).name, "STATE_REJECTED")
            return
        }
        pending = null
        if (delivery.snapshot == null) clearRequired = false else policy.markSent(delivery.snapshot, delivery.sentAt)
        state = GarminBridgeState.Ready(requireNotNull(device).name)
        flush(send)
    }

    private fun matchesDelivery(message: GarminEnvelope, id: String): Boolean =
        message.replyTo == id && message.stream == stream && message.sequence == sequence

    private fun flush(send: (Map<String, Any>) -> Boolean) {
        val currentStream = stream ?: return
        if (device == null || incompatible || pending != null) return
        val current = snapshot
        if (!clearRequired && (current == null || !policy.shouldSend(current, clock.elapsed()))) return
        sequence++
        val payload = if (clearRequired) null else current
        val message = if (payload ==
            null
        ) {
            codec.clearState(currentStream, sequence)
        } else {
            codec.snapshot(payload, currentStream, sequence)
        }
        pending = Delivery(message, payload, clock.elapsed(), 1)
        send(message.payload) // Enqueueing is not persistence acceptance; only correlated ack completes delivery.
    }

    private fun sendHello(send: (Map<String, Any>) -> Boolean) {
        hello?.let { send(it.payload) }
        helloAttempts++
        helloAt = clock.elapsed()
    }

    private fun resetDelivery() {
        stream = null
        sequence = 0L
        clearRequired = true
        pending = null
        hello = null
        helloAttempts = 0
        incompatible = false
        policy.reset()
    }

    private data class Delivery(
        val message: OutgoingGarminMessage,
        val snapshot: GarminPlaybackSnapshot?,
        val sentAt: Duration,
        val attempts: Int,
    )

    private companion object {
        const val MAX_ATTEMPTS = 3
        val ACK_TIMEOUT = 10.seconds
    }
}
