package com.example.shelfplayer.core.network.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PRODUCT_SPEC 10.3 / 15 — the ambient bearer reaches the issuing server and nothing else.
 *
 * ### The defect this exists to prevent
 *
 * `PlaybackService` is exported, and has to be: Android Auto, Bluetooth and the platform media controls
 * all reach a `MediaSession` through it, and an unexported service is unreachable by every one of them.
 * A Media3 controller may submit a pre-resolved stream item, and `MediaItems.isReadyToPlay` accepts one
 * carrying any `localConfiguration` — a bare URI qualifies. The media streaming client is a clone of the
 * authenticated client, so it carries this interceptor.
 *
 * Before the origin check, that chain ended here: another application could name a host it controlled and
 * have BookWave attach the user's Audiobookshelf bearer to a request sent there. The playback layer will
 * grow its own caller check; this is the second boundary, at the transport, and either alone would do.
 *
 * Two `MockWebServer` instances give two genuinely different origins on one machine, which is what makes
 * the negative case a real request rather than an assertion about a string.
 */
class AuthorizationInterceptorTest {

    private lateinit var issuer: MockWebServer
    private lateinit var attacker: MockWebServer

    @Before
    fun start() {
        issuer = MockWebServer().apply { start() }
        attacker = MockWebServer().apply { start() }
    }

    @After
    fun stop() {
        issuer.shutdown()
        attacker.shutdown()
    }

    @Test
    fun `the token is attached to the server that issued it`() {
        val recorded = call(client(issuer.url("/").toString()), issuer, "/api/libraries")

        assertEquals("Bearer session-token", recorded)
    }

    /**
     * **The one that matters.**
     *
     * A different port on the same host is a different origin, which is exactly the shape a malicious
     * controller would produce on a device: same loopback or LAN host, a port it happens to own.
     */
    @Test
    fun `the token is withheld from an origin that did not issue it`() {
        val recorded = call(client(issuer.url("/").toString()), attacker, "/stream.mp3")

        assertNull(recorded, "the bearer must not reach a host the credential did not come from")
    }

    /** A caller that names its own credential is left alone, whichever origin it addresses. */
    @Test
    fun `an explicit authorization header is never replaced`() {
        val client = client(issuer.url("/").toString())
        issuer.enqueue(MockResponse())

        client.newCall(
            Request.Builder()
                .url(issuer.url("/api/libraries"))
                .header("Authorization", "Bearer explicit-profile-b")
                .build(),
        ).execute().close()

        assertEquals("Bearer explicit-profile-b", issuer.takeRequest().getHeader("Authorization"))
    }

    /** No credential, no header — and no crash. */
    @Test
    fun `no token means no header`() {
        val client = OkHttpClient.Builder().addInterceptor(AuthorizationInterceptor { null }).build()
        issuer.enqueue(MockResponse())

        client.newCall(Request.Builder().url(issuer.url("/api/libraries")).build()).execute().close()

        assertNull(issuer.takeRequest().getHeader("Authorization"))
    }

    /**
     * A base URL that will not parse withholds the token rather than falling back to attaching it.
     *
     * A credential withheld costs a `401` the caller already handles. A credential sent somewhere it
     * should not go cannot be recalled, so the unparseable case has to fail in the safe direction.
     */
    @Test
    fun `an unparseable server address withholds the token`() {
        val recorded = call(client("not a url"), issuer, "/api/libraries")

        assertNull(recorded)
    }

    /** Case and a default port are normalisation, not a different server. */
    @Test
    fun `scheme case and an explicit default port still match`() {
        val url = issuer.url("/")
        val shouty = "HTTP://${url.host.uppercase()}:${url.port}"

        assertEquals("Bearer session-token", call(client(shouty), issuer, "/api/libraries"))
    }

    /** A path on the base URL is not part of the origin: a server may be hosted under a sub-path. */
    @Test
    fun `a base url with a path still matches its own origin`() {
        val recorded = call(client(issuer.url("/audiobookshelf/").toString()), issuer, "/api/libraries")

        assertEquals("Bearer session-token", recorded)
    }

    private fun client(serverBaseUrl: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthorizationInterceptor { ActiveCredential("session-token", serverBaseUrl) })
        .build()

    /** Issues one request to [target] and returns the `Authorization` header it actually received. */
    private fun call(client: OkHttpClient, target: MockWebServer, path: String): String? {
        target.enqueue(MockResponse())
        client.newCall(Request.Builder().url(target.url(path)).build()).execute().close()
        return target.takeRequest().getHeader("Authorization")
    }
}
