package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.ServerProbe
import com.example.shelfplayer.core.model.auth.AuthSession
import com.example.shelfplayer.core.model.auth.AuthToken
import com.example.shelfplayer.core.network.http.NetworkErrorMapper
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC 17.1 — a data/contract test over the real Retrofit stack.
 *
 * The responses are the committed fixtures, captured from Audiobookshelf 2.36.0, so this covers the
 * whole path a request takes: the Retrofit interface, the header the login depends on, the JSON
 * deserialization, the mapper, and the status-to-[AppError] translation. Nothing here is hand-written
 * JSON standing in for a server.
 *
 * The client carries **no** `AuthorizationInterceptor`, matching `@UnauthenticatedClient` in production
 * (PRODUCT_SPEC 9.4). That is asserted rather than assumed: several tests check that no `Authorization`
 * header was sent, because the bug this arrangement prevents — one profile's token leaking to another
 * server during sign-in — is invisible otherwise.
 */
class AbsAuthContractTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AbsAuthApi

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
        coerceInputValues = false
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = AbsAuthApi(
            services = AudiobookshelfServiceFactory(OkHttpClient(), OkHttpClient(), json),
            errors = NetworkErrorMapper(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    // --- GET /status ------------------------------------------------------------------------------

    @Test
    fun `probe reads the application marker, the version and the authentication modes`() = runTest {
        server.enqueue(ContractFixtures.response("status-initialized"))

        val probe = assertIs<AppResult.Success<*>>(api.probe(baseUrl())).value as ServerProbe

        assertTrue(probe.isAudiobookshelf)
        assertEquals("2.36.0", probe.serverVersion)
        assertTrue(probe.isInitialized)
        assertEquals(listOf("local"), probe.authMethods)

        val request = server.takeRequest()
        assertEquals("/status", request.path)
        assertNull(request.getHeader("Authorization"), "a probe must not carry any credential")
    }

    /** A server that has not completed first-run setup reports `isInit: false`, and the app must see it. */
    @Test
    fun `probe reports an uninitialized server`() = runTest {
        server.enqueue(ContractFixtures.response("status-uninitialized"))

        val probe = assertIs<AppResult.Success<*>>(api.probe(baseUrl())).value as ServerProbe

        assertTrue(probe.isAudiobookshelf)
        assertTrue(!probe.isInitialized)
    }

    @Test
    fun `a host that answers with something else is not an Audiobookshelf server`() = runTest {
        server.enqueue(MockResponse().setBody("""{"app":"something-else"}"""))

        val probe = assertIs<AppResult.Success<*>>(api.probe(baseUrl())).value as ServerProbe

        assertTrue(!probe.isAudiobookshelf)
    }

    // --- POST /login ------------------------------------------------------------------------------

    /**
     * The header the whole mobile token model depends on. Verified against a real server both ways:
     * without it the body carries `refreshToken: null` and the value is set as an HttpOnly cookie a
     * native client cannot read.
     */
    @Test
    fun `login sends x-return-tokens and reads both tokens from under user`() = runTest {
        server.enqueue(ContractFixtures.response("login"))

        val session = assertIs<AppResult.Success<*>>(api.signIn(baseUrl(), "contractroot", "pw")).value as AuthSession

        assertEquals("contractroot", session.username)
        assertEquals("<redacted-secret>", session.accessToken.value)
        assertTrue(session.isRenewable)
        assertTrue(session.access.hasAllLibraryAccess)

        val request = server.takeRequest()
        assertEquals("/login", request.path)
        assertEquals("true", request.getHeader("x-return-tokens"))
        assertNull(request.getHeader("Authorization"), "signing in must not carry another profile's token")
        assertTrue(request.body.readUtf8().contains("contractroot"))
    }

    @Test
    fun `a wrong password is an authentication failure, not a crash`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val error = assertIs<AppResult.Failure>(api.signIn(baseUrl(), "contractroot", "wrong")).error

        assertIs<AppError.Authentication>(error)
        assertTrue(!error.isRetryable, "PRODUCT_SPEC 14.3: authentication is never blind-retried")
    }

    /** PRODUCT_SPEC SYNC-001 — a missing field is a typed compatibility error naming the field. */
    @Test
    fun `a login response with no token names the missing field`() = runTest {
        server.enqueue(MockResponse().setBody("""{"user":{"username":"someone"}}"""))

        val error = assertIs<AppResult.Failure>(api.signIn(baseUrl(), "someone", "pw")).error

        assertEquals("user.accessToken", assertIs<AppError.ApiCompatibility>(error).missingField)
    }

    // --- POST /auth/refresh -----------------------------------------------------------------------

    /**
     * The refresh token travels in a header, not a cookie and not the body — and the response carries a
     * *new* refresh token, which is why the session layer replaces both.
     */
    @Test
    fun `refresh sends the token in x-refresh-token and receives a renewed session`() = runTest {
        server.enqueue(ContractFixtures.response("auth-refresh"))

        val session = assertIs<AppResult.Success<*>>(
            api.refresh(baseUrl(), AuthToken("stored-refresh")),
        ).value as AuthSession

        assertTrue(session.isRenewable, "the renewed session must itself be renewable")

        val request = server.takeRequest()
        assertEquals("/auth/refresh", request.path)
        assertEquals("stored-refresh", request.getHeader("x-refresh-token"))
    }

    @Test
    fun `a refused refresh is an authentication failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        assertIs<AppError.Authentication>(
            assertIs<AppResult.Failure>(api.refresh(baseUrl(), AuthToken("stale"))).error,
        )
    }

    // --- POST /logout -----------------------------------------------------------------------------

    /** PRODUCT_SPEC 5.2 — sign-out carries the credential it was given, not an ambient one. */
    @Test
    fun `signing out sends the supplied token explicitly`() = runTest {
        server.enqueue(ContractFixtures.response("logout"))

        assertIs<AppResult.Success<*>>(api.signOut(baseUrl(), AuthToken("that-profiles-token")))

        val request = server.takeRequest()
        assertEquals("/logout", request.path)
        assertEquals("Bearer that-profiles-token", request.getHeader("Authorization"))
    }

    // --- Base URL handling ------------------------------------------------------------------------

    /**
     * A server behind a reverse proxy at `https://host/abs` must resolve `login` under the subpath.
     * Retrofit silently drops the last path segment of a base URL without a trailing slash, which turns
     * into 404s that look like the server is wrong rather than the URL handling.
     */
    @Test
    fun `a server on a subpath keeps it`() = runTest {
        server.enqueue(ContractFixtures.response("status-initialized"))

        api.probe("${baseUrl()}/abs")

        assertEquals("/abs/status", server.takeRequest().path)
    }

    // --- GET /status as the capability handshake ---------------------------------------------------

    /**
     * PRODUCT_SPEC SYNC-001 — the handshake persists the version and the authentication mode, and
     * confirms no capability, because nothing `/status` reports is one.
     */
    @Test
    fun `the capability handshake confirms nothing and reports what status does carry`() = runTest {
        server.enqueue(ContractFixtures.response("status-initialized"))
        val resolver = AbsCapabilityResolver(
            services = AudiobookshelfServiceFactory(OkHttpClient(), OkHttpClient(), json),
            errors = NetworkErrorMapper(),
        )

        val capabilities = assertIs<AppResult.Success<*>>(
            resolver.resolve(ServerId("srv_1"), baseUrl()),
        ).value as ServerCapabilities

        assertEquals("2.36.0", capabilities.serverVersion)
        assertEquals(listOf("local"), capabilities.authMethods)
        assertEquals(emptySet(), capabilities.supported)
    }

    @Test
    fun `a handshake against a host that is not Audiobookshelf is a compatibility failure`() = runTest {
        server.enqueue(MockResponse().setBody("""{"hello":"world"}"""))
        val resolver = AbsCapabilityResolver(
            services = AudiobookshelfServiceFactory(OkHttpClient(), OkHttpClient(), json),
            errors = NetworkErrorMapper(),
        )

        val error = assertIs<AppResult.Failure>(resolver.resolve(ServerId("srv_1"), baseUrl())).error

        assertEquals("app", assertIs<AppError.ApiCompatibility>(error).missingField)
    }

    @Test
    fun `a server error during the handshake is reported as retryable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val resolver = AbsCapabilityResolver(
            services = AudiobookshelfServiceFactory(OkHttpClient(), OkHttpClient(), json),
            errors = NetworkErrorMapper(),
        )

        val error = assertIs<AppResult.Failure>(resolver.resolve(ServerId("srv_1"), baseUrl())).error

        assertTrue(error.isRetryable)
    }
}
