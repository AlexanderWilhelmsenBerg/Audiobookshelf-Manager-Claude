package com.example.shelfplayer.core.network.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

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
    @POST("api/authorize")
    suspend fun authorize(): Response<LoginResponseDto>

    @POST("logout")
    suspend fun logout(): Response<Unit>
}
