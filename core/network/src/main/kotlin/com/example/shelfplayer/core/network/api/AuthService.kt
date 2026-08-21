package com.example.shelfplayer.core.network.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * The authentication endpoints, verified against Audiobookshelf 2.36.0 on 2026-08-05.
 *
 * None of these appear in the project's published `openapi.json`. They are recorded in
 * `docs/api-compatibility.md` and captured by `.github/workflows/contract-capture.yml`
 * (PRODUCT_SPEC 22.4, 22.5, 22.19).
 *
 * Every call returns `Response<T>` rather than `T` so the status code reaches
 * [com.example.shelfplayer.core.network.http.NetworkErrorMapper] — `401` on `/login` is a wrong
 * password and `401` on `/api/authorize` is an expired token, and the two lead to different places
 * (PRODUCT_SPEC AUTH-004).
 */
internal interface AuthService {
    @GET("status")
    suspend fun status(): Response<ServerStatusDto>

    /**
     * `x-return-tokens: true` is not optional for this client.
     *
     * Without it the server returns `refreshToken: null` and sets the value as an `HttpOnly` cookie,
     * which a native app cannot read — so the session would authenticate once and then be
     * unrenewable. Verified both ways against 2.36.0.
     */
    @Headers("x-return-tokens: true")
    @POST("login")
    suspend fun login(@Body request: LoginRequestDto): Response<LoginResponseDto>

    /** The refresh token travels in a header, not a cookie and not the body. */
    @POST("auth/refresh")
    suspend fun refresh(@Header("x-refresh-token") refreshToken: String): Response<RefreshResponseDto>

    /**
     * Exchanges a stored token for the current user and permissions on a cold start.
     *
     * A `401` here is the signal that `AUTH-004` acts on: the stored token is no longer good, and the
     * profile is marked as requiring reauthentication rather than being silently signed out.
     */
    /**
     * PRODUCT_SPEC SYNC-001 — the websocket capability, probed rather than assumed.
     *
     * engine.io's opening handshake over the polling transport, which is plain HTTP. The response is a
     * `0{...}` frame naming the transports it can upgrade to; `websocket` in that list is the capability.
     *
     * Deliberately unauthenticated and read-only: it establishes a session the app immediately abandons,
     * which is the cheapest question that gets a truthful answer. Asking the *version* instead would be
     * the guess PRODUCT_SPEC 10.4 warns about — a reverse proxy that strips the upgrade runs the same
     * server version as one that does not.
     */
    @GET("socket.io/")
    suspend fun socketHandshake(
        @Query("EIO") engineIoVersion: String = "4",
        @Query("transport") transport: String = "polling",
    ): Response<ResponseBody>

    @POST("api/authorize")
    suspend fun authorize(@Header(AUTHORIZATION) bearer: String): Response<LoginResponseDto>

    /**
     * PRODUCT_SPEC SYNC-001 / MGR-003 — the metadata providers this deployment offers.
     *
     * The only endpoint in EPIC MGR that is an honest capability probe. It is read-only, has no side
     * effects, needs no privilege beyond being signed in, and answers a question that genuinely varies
     * between deployments rather than between versions: a server administrator can configure custom
     * providers, and the list reflects them.
     *
     * A server too old to have the route answers `404`, which is exactly the "not confirmed" signal
     * SYNC-001 wants and costs no version comparison (PRODUCT_SPEC 10.4).
     *
     * Source-derived, not yet captured — see `docs/api-compatibility.md`, "What the official project's
     * own source settles". [MetadataProvidersDto] therefore reads as *unsupported* on any shape it does
     * not recognise, which is what keeps PRODUCT_SPEC 22.5 satisfied until the fixture lands.
     */
    @GET("api/search/providers")
    suspend fun metadataProviders(@Header(AUTHORIZATION) bearer: String): Response<MetadataProvidersDto>

    @POST("logout")
    suspend fun logout(@Header(AUTHORIZATION) bearer: String): Response<Unit>
}

/**
 * The credential travels as an explicit parameter, not as an ambient interceptor header.
 *
 * These services run on the unauthenticated client (PRODUCT_SPEC 9.4), so the token a call uses is
 * the token its caller chose. That is what makes it impossible for a sign-out or an authorize on
 * profile B to be signed with profile A's credential (PRODUCT_SPEC 5.2).
 */
internal const val AUTHORIZATION = "Authorization"

internal fun bearerOf(token: String): String = "Bearer $token"
