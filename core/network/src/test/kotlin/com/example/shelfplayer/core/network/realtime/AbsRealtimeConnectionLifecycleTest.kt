package com.example.shelfplayer.core.network.realtime

import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.auth.AuthToken
import com.example.shelfplayer.core.model.auth.LibraryAccess
import com.example.shelfplayer.core.model.realtime.RealtimeStatus
import com.example.shelfplayer.core.network.gateway.ProfileConnection
import com.example.shelfplayer.core.network.gateway.ProfileConnectionResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AbsRealtimeConnectionLifecycleTest {

    @Test
    fun `cancelling the collector cancels its websocket`() = runBlocking {
        MockWebServer().use { server ->
            val opened = CompletableDeferred<Unit>()
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            opened.complete(Unit)
                        }
                    },
                ),
            )
            server.start()

            val client = OkHttpClient()
            val realtime = AbsRealtimeConnection(
                client = client,
                connections = FixedConnectionResolver(server.url("/").toString().trimEnd('/')),
                logger = NO_OP_LOGGER,
                random = Random(0),
            )
            val collector = launch { realtime.events(PROFILE).collect() }

            withTimeout(TEST_TIMEOUT_MILLIS) { opened.await() }
            assertTrue(client.dispatcher.runningCallsCount() > 0)

            collector.cancelAndJoin()

            withTimeout(TEST_TIMEOUT_MILLIS) {
                while (client.dispatcher.runningCallsCount() > 0) delay(POLL_MILLIS)
            }
            assertEquals(0, client.dispatcher.runningCallsCount())
            assertEquals(RealtimeStatus.Idle, realtime.status.value)

            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private class FixedConnectionResolver(private val serverUrl: String) : ProfileConnectionResolver {
        override suspend fun connectionFor(profileId: ProfileId): ProfileConnection = ProfileConnection(
            profileId = profileId,
            serverId = SERVER,
            serverUrl = serverUrl,
            accessToken = AuthToken("fixture-token"),
            access = LibraryAccess.None,
        )
    }

    private companion object {
        val PROFILE = ProfileId("profile-a")
        val SERVER = ServerId("server-a")
        const val TEST_TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 10L

        val NO_OP_LOGGER = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
    }
}
