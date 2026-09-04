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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
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
 * The flow owns the socket lifetime. Every socket is cancelled from `finally`, so profile switches and
 * collector cancellation have the same cleanup guarantee as a normal server-side close.
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

    override fun events(profileId: ProfileId): Flow<RealtimeEvent> = callbackFlow {
        var attempt = 0
        try {
            while (isActive) {
                val connection = connections.connectionFor(profileId)
                if (connection == null) {
                    state.value = RealtimeStatus.Idle
                    break
                }

                state.value = RealtimeStatus.Connecting
                val closed = CompletableDeferred<Unit>()
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

                try {
                    closed.await()
                } finally {
                    // Cancellation while `closed.await()` is suspended used to skip this call completely.
                    // Keeping it in `finally` makes the Flow collector the authoritative socket lifetime.
                    socket.cancel()
                }

                if (!isActive) break
                state.value = RealtimeStatus.Disconnected
                delay(backoffFor(attempt))
                attempt = min(attempt + 1, MAX_BACKOFF_STEP)
            }

            // A profile with no usable credential remains an idle flow until its collector is replaced.
            awaitClose { }
        } finally {
            state.value = RealtimeStatus.Idle
        }
    }

    private fun listener(token: String, onEvent: (RealtimeEvent) -> Unit, onClosed: () -> Unit) =
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val frame = EngineIoFrames.parse(text)) {
                    is IncomingFrame.Handshake -> {
                        webSocket.send(EngineIoFrames.NAMESPACE_CONNECT)
                        webSocket.send(EngineIoFrames.authFrame(token))
                    }

                    IncomingFrame.Ping -> webSocket.send(EngineIoFrames.PONG_FRAME)
                    IncomingFrame.NamespaceConnected -> Unit
                    IncomingFrame.Closed -> onClosed()
                    is IncomingFrame.Event -> handle(frame)?.let(onEvent)
                    null -> Unit
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                logger.warn(
                    LogCategory.Sync,
                    "The realtime connection failed",
                    LogField.Public("httpStatus", response?.code ?: 0),
                )
                onClosed()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed()
        }

    private fun handle(frame: IncomingFrame.Event): RealtimeEvent? = when (frame.name) {
        INIT_EVENT -> {
            state.value = RealtimeStatus.Connected
            logger.info(LogCategory.Sync, "The realtime connection is authenticated")
            null
        }

        USER_UPDATED_EVENT -> frame.body?.let(::accountChanged)
        TASK_STARTED_EVENT, TASK_FINISHED_EVENT -> frame.body?.let(::taskChanged)
        else -> null
    }

    private fun accountChanged(body: JsonObject): RealtimeEvent? =
        AuthMapper.toAccountState(json, body)?.let(RealtimeEvent::AccountChanged)

    private fun taskChanged(body: JsonObject): RealtimeEvent? = TaskFrames.parse(body)?.let(RealtimeEvent::TaskChanged)

    private fun backoffFor(attempt: Int): Long =
        random.nextLong(min(BASE_BACKOFF_MILLIS shl attempt, MAX_BACKOFF_MILLIS) + 1)

    private fun socketUrlFor(serverUrl: String): String =
        "${serverUrl.trimEnd('/')}/socket.io/?EIO=4&transport=websocket"

    private companion object {
        const val INIT_EVENT = "init"
        const val USER_UPDATED_EVENT = "user_updated"
        const val TASK_STARTED_EVENT = "task_started"
        const val TASK_FINISHED_EVENT = "task_finished"
        const val BASE_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 60_000L
        const val MAX_BACKOFF_STEP = 6
    }
}
