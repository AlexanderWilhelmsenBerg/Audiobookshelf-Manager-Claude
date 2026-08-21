package com.example.shelfplayer.core.network.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import kotlin.test.assertEquals

/** PRODUCT_SPEC 10.3 / DL-001 — streaming endpoints must not silently fall back to the API client. */
class AudiobookshelfServiceFactoryTest {

    @Test
    fun `download service uses the dedicated streaming client`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("audio"))
            val services = AudiobookshelfServiceFactory(
                authenticatedClient = markedClient("api"),
                unauthenticatedClient = OkHttpClient(),
                downloadStreamingClient = markedClient("download-streaming"),
                json = Json { ignoreUnknownKeys = true },
            )

            services.downloadService(server.url("/").toString()).file(
                bearer = "Bearer token",
                itemId = "item",
                fileId = "file",
                range = null,
                ifRange = null,
            ).body()?.close()

            assertEquals("download-streaming", server.takeRequest().getHeader(CLIENT_POLICY_HEADER))
        } finally {
            server.shutdown()
        }
    }

    private fun markedClient(policy: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header(CLIENT_POLICY_HEADER, policy)
                        .build(),
                )
            },
        )
        .build()

    private companion object {
        const val CLIENT_POLICY_HEADER = "X-BookWave-Client-Policy"
    }
}
