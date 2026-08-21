package com.example.shelfplayer.core.network.realtime

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.realtime.RealtimeEvent
import com.example.shelfplayer.core.model.realtime.RealtimeStatus
import com.example.shelfplayer.core.network.api.AuthMapper
import com.example.shelfplayer.core.network.di.UnauthenticatedClient
import com.example.shelfplayer.core.network.gateway.ProfileConnectionResolver
import com.example.shelfplayer.core.network.gateway.RealtimeConnection
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.random.Random

/**
 * PRODUCT_SPEC SYNC-002 / LIB-001 — the realtime connection, over socket.io's websocket transport.
 *
 * The sequence is the one `scripts/capture-contracts.sh` recorded against Audiobookshelf 2.36.0:
 * open → `40` → `42["auth", token]` → `user_online`, `init`, and thereafter `user_updated` whenever
 * the account changes. Every frame is parsed by [EngineIoFrames], which is where the observed shapes
 * live and where they are tested.
 *
 * ### It is an optimisation, not a source of truth
 *
 * PRODUCT_SPEC LIB-001 requires REST refresh when the websocket is unavailable, and this client is
 * built so that "unavailable" is uneventful: it emits events when connected and nothing at all when
 * not. Everything it delivers also arrives through `SyncAccountUseCase` on resume and on profile
 * switch. A self-hosted server behind a reverse proxy that strips the upgrade therefore loses latency
 * and nothing else — which is the deployment most likely to be in that position.
 *
 * ### The websocket transport, not polling
 *
 * The capture used polling because it is plain HTTP and needs no client library. This uses the
 * upgrade, because a polling client holds a request open and reissues it forever, which on a phone is
 * a radio that never sleeps. The frames are identical; only the carriage differs.
 *
 * ### Reconnection
 *
 * Bounded exponential backoff with full jitter, unbounded in attempts while a profile is active —
 * PRODUCT_SPEC 14.3 says "reconnect indefinitely with capped backoff while profile remains active",
 * and that is a different rule from the retry budget a request gets. Jitter matters more here than
 * for requests: every client of a server that restarted would otherwise reconnect in the same second.
 */
@Singleton
internal class AbsRealtimeConnection @Inject constructor(
    @param:UnauthenticatedClient private val client: OkHttpClient,
    private val connections: ProfileConnectionResolver,
    private val logger: Logger,
    private val random: Random,
) : RealtimeConnection {

    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(RealtimeStatus.Idle)

    override val status: StateFlow<RealtimeStatus> = state

    /**
     * Connects for [profileId] and emits until cancelled.
     *
     * Cancelling the collector closes the socket — the flow *is* the connection's lifetime, which is
     * what makes "connected only while something is listening" true without a separate stop call to
     * forget.
     */
    override fun events(profileId: ProfileId): Flow<RealtimeEvent> = callbackFlow {
        var attempt = 0
        while (true) {
            val connection = connections.connectionFor(profileId)
            if (connection == null) {
                // No credential means nothing to authenticate with. Not an error and not worth
                // retrying: a profile that needs to sign in again will not acquire a token by waiting.
                state.value = RealtimeStatus.Idle
                break
            }

            state.value = RealtimeStatus.Connecting
            val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val request = Request.Builder()
                .url(socketUrlFor(connection.serverUrl))
                .build()

            val socket = client.newWebSocket(
                request,
                listener(
                    token = connection.accessToken.value,
                    onEvent = { event -> trySend(event) },
                    onClosed = { if (!closed.isCompleted) closed.complete(Unit) },
                ),
            )

            closed.await()
            socket.cancel()
            state.value = RealtimeStatus.Disconnected
            delay(backoffFor(attempt))
            attempt = min(attempt + 1, MAX_BACKOFF_STEP)
        }
        awaitClose { state.value = RealtimeStatus.Idle }
    }

    private fun listener(token: String, onEvent: (RealtimeEvent) -> Unit, onClosed: () -> Unit) =
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val frame = EngineIoFrames.parse(text)) {
                    is IncomingFrame.Handshake -> {
                        // The namespace connect has to come first; the server sends no events at all
                        // until it has been joined, so a client that skipped it would sit silent and
                        // look healthy.
                        webSocket.send(EngineIoFrames.NAMESPACE_CONNECT)
                        webSocket.send(EngineIoFrames.authFrame(token))
                    }

                    // Answering the heartbeat is not optional: the server closes a connection that
                    // stops ponging, and a phone that missed one would silently stop receiving events.
                    IncomingFrame.Ping -> webSocket.send(EngineIoFrames.PONG_FRAME)

                    IncomingFrame.NamespaceConnected -> Unit

                    IncomingFrame.Closed -> onClosed()

                    is IncomingFrame.Event -> handle(frame)?.let(onEvent)

                    null -> Unit
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // PRODUCT_SPEC SYNC-002 — "reverse-proxy websocket errors are diagnosable". The status
                // code is the diagnosis: a proxy that refuses the upgrade answers 400 or 501 rather
                // than failing to connect, and without it the log would say only "it did not work".
                logger.warn(
                    LogCategory.Sync,
                    "The realtime connection failed",
                    LogField.Public("httpStatus", response?.code ?: 0),
                )
                onClosed()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed()
        }

    /**
     * PRODUCT_SPEC 13.2 — an event is applied, not merely noticed, but only one is understood.
     *
     * `init` and `user_online` are acknowledgements rather than news: they confirm the authentication
     * worked, which is worth a status change and nothing else. Anything unrecognised is ignored, so a
     * server that grows a new event does not break a client that has never heard of it.
     */
    private fun handle(frame: IncomingFrame.Event): RealtimeEvent? = when (frame.name) {
        INIT_EVENT -> {
            state.value = RealtimeStatus.Connected
            logger.info(LogCategory.Sync, "The realtime connection is authenticated")
            null
        }

        USER_UPDATED_EVENT -> frame.body?.let(::accountChanged)
        // PRODUCT_SPEC MGR-007 — both, because the pair is what makes progress visible: `task_started`
        // says the server picked the job up, and `task_finished` carries the outcome. The same shape
        // arrives for tasks this app never asked for; the reader filters by action and item.
        TASK_STARTED_EVENT, TASK_FINISHED_EVENT -> frame.body?.let(::taskChanged)
        else -> null
    }

    /** The decode and the mapping both live with the other `UserDto` handling; see [AuthMapper]. */
    private fun accountChanged(body: JsonObject): RealtimeEvent? =
        AuthMapper.toAccountState(json, body)?.let(RealtimeEvent::AccountChanged)

    /** PRODUCT_SPEC MGR-007 — the decode lives in [TaskFrames], which documents what it refuses to read. */
    private fun taskChanged(body: JsonObject): RealtimeEvent? = TaskFrames.parse(body)?.let(RealtimeEvent::TaskChanged)

    /**
     * Full jitter, and capped.
     *
     * A server that restarted has every client of it reconnecting at once; a fixed schedule would
     * deliver them all in the same second and knock it over again.
     */
    private fun backoffFor(attempt: Int): Long =
        random.nextLong(min(BASE_BACKOFF_MILLIS shl attempt, MAX_BACKOFF_MILLIS) + 1)

    private fun socketUrlFor(serverUrl: String): String =
        "${serverUrl.trimEnd('/')}/socket.io/?EIO=4&transport=websocket"

    private companion object {
        const val INIT_EVENT = "init"
        const val USER_UPDATED_EVENT = "user_updated"

        /** PRODUCT_SPEC MGR-007 — `TaskManager` emits both with the task's whole JSON. */
        const val TASK_STARTED_EVENT = "task_started"
        const val TASK_FINISHED_EVENT = "task_finished"

        const val BASE_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 60_000L

        /** `1_000 shl 6` is already past the cap; further shifting would overflow for no benefit. */
        const val MAX_BACKOFF_STEP = 6
    }
}
