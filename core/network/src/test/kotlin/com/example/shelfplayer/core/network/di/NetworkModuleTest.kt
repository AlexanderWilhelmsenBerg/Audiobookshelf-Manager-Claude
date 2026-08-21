package com.example.shelfplayer.core.network.di

import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.NoOpLogSink
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.common.time.ServerClock
import com.example.shelfplayer.core.network.http.AuthorizationInterceptor
import com.example.shelfplayer.core.network.http.RedactingHttpLoggingInterceptor
import com.example.shelfplayer.core.network.http.ServerClockInterceptor
import com.example.shelfplayer.core.network.http.UserAgent
import com.example.shelfplayer.core.network.http.UserAgentInterceptor
import com.example.shelfplayer.core.testing.TestAppClock
import okhttp3.OkHttpClient
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** PRODUCT_SPEC 10.3 / PLAY-006 / DL-001 — endpoint lifetime is part of the HTTP client contract. */
class NetworkModuleTest {

    private val authenticatedClient = NetworkModule.providesAuthenticatedClient(
        authorization = AuthorizationInterceptor { null },
        userAgent = UserAgentInterceptor(UserAgent("BookWave/Test")),
        logging = RedactingHttpLoggingInterceptor(logger, clock),
        serverClock = ServerClockInterceptor(ServerClock(logger), clock),
    )

    @Test
    fun `ordinary API calls retain their bounded total lifetime`() {
        assertEquals(60_000, authenticatedClient.callTimeoutMillis)
        assertEquals(15_000, authenticatedClient.connectTimeoutMillis)
        assertEquals(30_000, authenticatedClient.readTimeoutMillis)
        assertEquals(30_000, authenticatedClient.writeTimeoutMillis)
    }

    @Test
    fun `Media3 streaming has no whole-response deadline but keeps stall timeouts`() {
        val client = NetworkModule.providesMediaStreamingClient(authenticatedClient)

        assertLongLivedPolicy(client)
    }

    @Test
    fun `streaming downloads have no whole-response deadline but keep stall timeouts`() {
        val client = NetworkModule.providesDownloadStreamingClient(authenticatedClient)

        assertLongLivedPolicy(client)
    }

    private fun assertLongLivedPolicy(client: OkHttpClient) {
        assertNotSame(authenticatedClient, client)
        assertEquals(0, client.callTimeoutMillis)
        assertEquals(authenticatedClient.connectTimeoutMillis, client.connectTimeoutMillis)
        assertEquals(authenticatedClient.readTimeoutMillis, client.readTimeoutMillis)
        assertEquals(authenticatedClient.writeTimeoutMillis, client.writeTimeoutMillis)
        assertEquals(authenticatedClient.interceptors, client.interceptors)
        assertSame(authenticatedClient.connectionPool, client.connectionPool)
        assertSame(authenticatedClient.dispatcher, client.dispatcher)
    }

    private companion object {
        val clock = TestAppClock()
        val logger = RedactingLogger(NoOpLogSink(), DefaultRedactor(RedactionPolicy.Default))
    }
}
