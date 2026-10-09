package com.example.shelfplayer.garmin

import com.example.shelfplayer.core.common.time.AppClock
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class GarminDeliverySessionTest {
    @Test
    fun metadataWaitsForNegotiationAndDurableClearAcknowledgement() {
        val f = Fixture()
        f.session.select(f.snapshot, 1, f.send)
        f.connect()
        assertEquals(listOf("hello"), f.sent.map { it["t"] })
        f.hello()
        assertEquals("clear_state", f.last()["t"])
        assertTrue(f.sent.none { it["t"] == "snapshot" })
        f.ack()
        assertEquals("snapshot", f.last()["t"])
        f.ack()
        assertIs<GarminBridgeState.Ready>(f.session.state)
    }

    @Test
    fun wrongTypeMalformedAndOldAcknowledgementsCannotConsumePendingDelivery() {
        val f = Fixture().ready()
        f.session.select(null, 2, f.send)
        val clear = f.last()
        f.ack(type = "snapshot_ack")
        f.ack(accepted = "true")
        f.ack(stream = "old-stream")
        f.clock.elapsed = 10.seconds
        f.session.tick(f.send)
        assertEquals(clear, f.last())
        f.ack()
        assertIs<GarminBridgeState.Ready>(f.session.state)
    }

    @Test
    fun pausedSnapshotAndPrivacyClearRetryAtMostThreeTimes() {
        val f = Fixture().ready()
        f.session.select(f.snapshot.copy(title = "Changed", playing = false), 1, f.send)
        val message = f.last()
        repeat(5) {
            f.clock.elapsed += 10.seconds
            f.session.tick(f.send)
        }
        assertEquals(3, f.sent.count { it["id"] == message["id"] })
        assertIs<GarminBridgeState.AppUnavailable>(f.session.state)
        f.session.select(null, 2, f.send)
        val clear = f.last()
        repeat(5) {
            f.clock.elapsed += 10.seconds
            f.session.tick(f.send)
        }
        assertEquals(3, f.sent.count { it["id"] == clear["id"] })
    }

    @Test
    fun reconnectAndProfileRoundTripDiscardPendingPrivateMetadata() {
        val f = Fixture().ready()
        f.session.select(f.snapshot.copy(title = "Pending private"), 1, f.send)
        val stale = f.last()
        f.session.select(null, 3, f.send)
        f.session.connection(GarminSdkState.DeviceDisconnected(f.device), f.send)
        f.connect()
        f.hello("watch-new")
        val clear = f.last()
        f.session.receive(
            f.envelope("snapshot_ack", "stale", mapOf("accepted" to true)) +
                mapOf("r" to stale["id"], "s" to stale["s"], "n" to stale["n"]),
            f.send,
        )
        assertEquals(clear, f.last())
        f.ack()
        assertTrue(f.sent.dropWhile { it !== clear }.none { it["t"] == "snapshot" })
    }

    @Test
    fun incompatibleCapabilityNeverSendsMetadataOrAcceptsStateRequests() {
        val f = Fixture()
        f.session.select(f.snapshot, 1, f.send)
        f.connect()
        f.hello(caps = emptyList())
        assertIs<GarminBridgeState.ProtocolIncompatible>(f.session.state)
        f.session.receive(f.envelope("state_request", "request", emptyMap()) + ("s" to "watch-1"), f.send)
        assertTrue(f.sent.none { it["t"] == "snapshot" || it["t"] == "clear_state" })
    }

    @Test
    fun sdkEnqueueFailureStillRetriesAndNewerRewindReplacesPendingStateAfterAck() {
        val f = Fixture().ready()
        f.acceptSend = false
        f.session.select(f.snapshot.copy(positionMs = 80_000), 1, f.send)
        val pending = f.last()
        f.session.select(f.snapshot.copy(positionMs = 1_000), 1, f.send)
        assertEquals(pending, f.last())
        f.clock.elapsed = 10.seconds
        f.session.tick(f.send)
        assertEquals(pending, f.last())
        f.ack()
        assertEquals(1_000L, (f.last()["p"] as Map<*, *>)["positionMs"])
    }

    @Test
    fun stopAndDeviceChangeRequireNewHandshakeAndClear() {
        val f = Fixture().ready()
        f.session.connection(GarminSdkState.AppAvailable(f.device.copy(identifier = 2)), f.send)
        assertEquals("hello", f.last()["t"])
        f.hello("watch-2")
        assertEquals("clear_state", f.last()["t"])
        f.session.stop()
        val count = f.sent.size
        f.hello("after-stop")
        assertEquals(count, f.sent.size)
    }

    @Test
    fun unsupportedMajorIsVisibleAndCannotRequestPrivateState() {
        val f = Fixture().ready()
        f.session.receive(f.envelope("hello", "unsupported", mapOf("majors" to listOf(2))) + ("v" to 2), f.send)
        assertIs<GarminBridgeState.ProtocolIncompatible>(f.session.state)
        val count = f.sent.size
        f.session.receive(f.envelope("state_request", "request", emptyMap()) + ("s" to "watch-1"), f.send)
        f.clock.elapsed = 10.seconds
        f.session.tick(f.send)
        assertEquals(count, f.sent.size)
    }

    @Test
    fun watchLongMajorNegotiatesAndExplicitRetryRestartsAnExhaustedHandshake() {
        val f = Fixture()
        f.session.select(f.snapshot, 1, f.send)
        f.connect()
        repeat(4) {
            f.clock.elapsed += 10.seconds
            f.session.tick(f.send)
        }
        assertEquals(3, f.sent.count { it["t"] == "hello" })
        f.session.retry(f.send)
        assertEquals(4, f.sent.count { it["t"] == "hello" })
        f.session.receive(
            f.envelope(
                "hello",
                "watch-long",
                mapOf(
                    "majors" to listOf(1L),
                    "caps" to listOf("ordered_state"),
                ),
            ),
            f.send,
        )
        assertEquals("clear_state", f.last()["t"])
        f.ack()
        assertEquals("snapshot", f.last()["t"])
        f.ack()
        assertIs<GarminBridgeState.Ready>(f.session.state)
    }

    private class Fixture {
        val clock = Clock()
        val session = GarminDeliverySession(GarminMessageCodec(clock), GarminSnapshotSendPolicy(), clock)
        val device = GarminDeviceRef(1, "Fixture watch")
        val sent = mutableListOf<Map<String, Any>>()
        var acceptSend = true
        val send: (Map<String, Any>) -> Boolean = {
            sent.add(it)
            acceptSend
        }
        val snapshot =
            GarminPlaybackSnapshot(
                1, "profile-a", "book-a", "Fixture", null, null,
                12_000, 100_000, 1_700_000_000_000, false,
            )
        fun connect() = session.connection(GarminSdkState.AppAvailable(device), send)
        fun hello(id: String = "watch-1", caps: List<String> = listOf("ordered_state")) =
            session.receive(envelope("hello", id, mapOf("majors" to listOf(1), "caps" to caps)), send)
        fun last() = sent.last()
        fun ack(
            type: String = if (last()["t"] == "clear_state") "clear_ack" else "snapshot_ack",
            accepted: Any = true,
            stream: Any? = last()["s"],
        ) {
            session.receive(
                envelope(type, "ack", mapOf("accepted" to accepted)) +
                    mapOf("r" to last()["id"], "s" to stream, "n" to last()["n"]),
                send,
            )
        }
        fun ready(): Fixture {
            session.select(snapshot, 1, send)
            connect()
            hello()
            ack()
            ack()
            return this
        }
        fun envelope(type: String, id: String, payload: Map<String, Any>): Map<String, Any?> =
            mapOf("v" to 1, "t" to type, "id" to id, "ts" to 1_700_000_000_000L, "p" to payload)
    }

    private class Clock : AppClock {
        var elapsed = Duration.ZERO
        override fun elapsed(): Duration = elapsed
        override fun now(): Instant = Instant.ofEpochMilli(1_700_000_000_000L + elapsed.inWholeMilliseconds)
    }
}
