package com.example.shelfplayer.core.network.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * PRODUCT_SPEC SYNC-002 / 22.5 — engine.io and socket.io framing, as observed.
 *
 * Every shape here comes from `contracts/socket-handshake.json`, `socket-connected.json`,
 * `socket-auth.json` and `socket-event-after-progress.json`, captured from Audiobookshelf 2.36.0 on
 * 2026-08-06 and pinned by `RealtimeContractTest`. Nothing is inferred from the protocol
 * specification: what the *server* does is the contract, and where the two disagree the capture wins.
 *
 * ### Why this is a pure object with no socket in it
 *
 * A frame parser that owns a connection can only be tested with a connection. Separating them means
 * the interesting half — what `42["user_updated", {…}]` means, what a `2` obliges the client to send
 * back — is decided by ordinary unit tests, and the socket is left with nothing to get wrong but I/O.
 */
internal object EngineIoFrames {

    private val json = Json { ignoreUnknownKeys = true }

    /** engine.io packet types, as the numeric prefix of every frame. */
    const val OPEN = '0'
    const val CLOSE = '1'
    const val PING = '2'
    const val PONG = '3'
    const val MESSAGE = '4'

    /** socket.io packet types, which follow a [MESSAGE] prefix. */
    private const val CONNECT = '0'
    private const val EVENT = '2'

    /** What the client sends to join the default namespace: a socket.io CONNECT inside a message. */
    const val NAMESPACE_CONNECT = "40"

    /** The reply engine.io expects to a [PING], and the only thing keeping the connection alive. */
    const val PONG_FRAME = "3"

    /**
     * The authentication frame.
     *
     * `auth` was a guess when the capture script first sent it and the server accepted it, answering
     * `user_online` then `init`. It is not in `openapi.json` and is documented nowhere else, which is
     * why the fixture and its test exist: if a future server renames it, that test fails rather than
     * this client connecting successfully and authenticating nobody.
     *
     * The token goes in the frame body. PRODUCT_SPEC 22.6 forbids putting it in a query string, and a
     * websocket URL is a query string that lands in every proxy log on the way.
     */
    fun authFrame(token: String): String = "42${JsonArray(listOf(str(AUTH_EVENT), str(token)))}"

    /**
     * Reads one incoming frame.
     *
     * `null` for anything this client does not act on — a `PONG`, a namespace connect acknowledgement,
     * an event it has no handler for. Unknown frames are ignored rather than treated as errors, because
     * PRODUCT_SPEC SYNC-001 requires unknown server behaviour to be inert rather than fatal, and a
     * server that adds an event must not break a client that has never heard of it.
     */
    fun parse(raw: String): IncomingFrame? {
        val frame = raw.trim()
        if (frame.isEmpty()) return null
        return when (frame.first()) {
            OPEN -> handshakeOf(frame.drop(1))
            PING -> IncomingFrame.Ping
            CLOSE -> IncomingFrame.Closed
            MESSAGE -> messageOf(frame.drop(1))
            else -> null
        }
    }

    private fun handshakeOf(payload: String): IncomingFrame? {
        val body = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val interval = body["pingInterval"]?.jsonPrimitive?.content?.toLongOrNull()
        return IncomingFrame.Handshake(pingIntervalMillis = interval)
    }

    private fun messageOf(payload: String): IncomingFrame? {
        if (payload.isEmpty()) return null
        return when (payload.first()) {
            CONNECT -> IncomingFrame.NamespaceConnected
            EVENT -> eventOf(payload.drop(1))
            else -> null
        }
    }

    /**
     * A socket.io EVENT payload is `["name", body]`.
     *
     * An event with no body — the capture shows none, but nothing forbids one — yields a null body
     * rather than being dropped, so a caller can still act on the name alone.
     */
    private fun eventOf(payload: String): IncomingFrame? {
        val array = runCatching { json.parseToJsonElement(payload) as? JsonArray }.getOrNull() ?: return null
        val name = array.firstOrNull()?.jsonPrimitive?.content ?: return null
        return IncomingFrame.Event(name = name, body = array.getOrNull(1) as? JsonObject)
    }

    private fun str(value: String) = kotlinx.serialization.json.JsonPrimitive(value)

    const val AUTH_EVENT = "auth"
}

/** One frame this client understands. Everything else is deliberately absent. */
internal sealed interface IncomingFrame {
    /**
     * The server's opening frame. [pingIntervalMillis] is how often it will ping; the capture recorded
     * 25 s with a 20 s timeout, but the value is read rather than assumed because it is configuration.
     */
    data class Handshake(val pingIntervalMillis: Long?) : IncomingFrame

    data object NamespaceConnected : IncomingFrame

    /** Answered with [EngineIoFrames.PONG_FRAME], or the server drops the connection. */
    data object Ping : IncomingFrame

    data object Closed : IncomingFrame

    data class Event(val name: String, val body: JsonObject?) : IncomingFrame
}
