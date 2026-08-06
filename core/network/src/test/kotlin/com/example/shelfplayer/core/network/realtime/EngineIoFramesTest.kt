package com.example.shelfplayer.core.network.realtime

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC SYNC-002 / 22.5 — the framing, against the frames a real server sent.
 *
 * Every input below is copied from a committed fixture rather than written from the protocol
 * specification. Where a specification and a server disagree it is the server that has to be talked
 * to, and this file is the record of which one this client believes.
 */
class EngineIoFramesTest {

    @Test
    fun `the opening frame reports the heartbeat interval`() {
        val frame = EngineIoFrames.parse(
            """0{"sid":"abc","upgrades":["websocket"],"pingInterval":25000,"pingTimeout":20000}""",
        )

        assertEquals(25_000L, assertIs<IncomingFrame.Handshake>(frame).pingIntervalMillis)
    }

    /** `40` is a socket.io CONNECT inside an engine.io MESSAGE, which is why it is two characters. */
    @Test
    fun `a namespace acknowledgement is recognised with or without a payload`() {
        assertIs<IncomingFrame.NamespaceConnected>(EngineIoFrames.parse("40"))
        assertIs<IncomingFrame.NamespaceConnected>(EngineIoFrames.parse("""40{"sid":"abc"}"""))
    }

    /**
     * Answering the heartbeat is what keeps the connection alive; a client that stops is disconnected
     * by the server, and would go quiet without ever seeing an error.
     */
    @Test
    fun `a ping is recognised and its answer is a bare 3`() {
        assertIs<IncomingFrame.Ping>(EngineIoFrames.parse("2"))
        assertEquals("3", EngineIoFrames.PONG_FRAME)
    }

    /** The event this whole transport exists to receive. */
    @Test
    fun `user_updated is parsed with its body`() {
        val frame = EngineIoFrames.parse("""42["user_updated",{"username":"ada","isActive":true}]""")

        val event = assertIs<IncomingFrame.Event>(frame)
        assertEquals("user_updated", event.name)
        assertEquals("ada", event.body?.get("username")?.jsonPrimitive?.content)
    }

    @Test
    fun `the authentication frame carries the token in the body, never in a url`() {
        assertEquals("""42["auth","secret-token"]""", EngineIoFrames.authFrame("secret-token"))
    }

    /**
     * PRODUCT_SPEC SYNC-001 — unknown server behaviour is inert, not fatal.
     *
     * A server that adds an event, or sends a frame type this client has no use for, must not break a
     * client that has never heard of it. Every one of these is ignored rather than throwing.
     */
    @Test
    fun `frames this client has no use for are ignored rather than fatal`() {
        listOf("", "3", "9", "4", "49", """42["something_new",{"a":1}]""", "42not-json", "0not-json")
            .forEach { raw ->
                val frame = EngineIoFrames.parse(raw)
                val inert = frame == null || frame is IncomingFrame.Event
                assertTrue(inert, "frame $raw should be inert, was ${frame ?: "ignored"}")
            }
    }

    @Test
    fun `a close frame ends the connection`() {
        assertIs<IncomingFrame.Closed>(EngineIoFrames.parse("1"))
    }

    @Test
    fun `an event with no name is not an event`() {
        assertNull(EngineIoFrames.parse("42[]"))
    }
}
