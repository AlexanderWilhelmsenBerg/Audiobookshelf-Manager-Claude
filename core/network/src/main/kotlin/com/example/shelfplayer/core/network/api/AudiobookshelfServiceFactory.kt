package com.example.shelfplayer.core.network.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the Retrofit services for one server.
 *
 * A factory rather than a `@Provides` singleton because the base URL is a property of the profile
 * being used, not of the application (PRODUCT_SPEC AUTH-002: several servers and several accounts
 * coexist). Injecting a single pre-built `AuthService` would bake one server's address into the
 * object graph and make profile switching impossible.
 *
 * The [OkHttpClient] is shared across servers on purpose: its connection pool, dispatcher and thread
 * pool are expensive, and Retrofit adds no per-host state that would make sharing unsafe.
 */
@Singleton
class AudiobookshelfServiceFactory @Inject constructor(private val client: OkHttpClient, private val json: Json) {
    internal fun authService(baseUrl: String): AuthService = retrofitFor(baseUrl).create(AuthService::class.java)

    /**
     * Retrofit rejects a base URL that does not end in `/`, and — worse — silently discards the last
     * path segment of one that does not. A server behind a reverse proxy at `https://host/abs` would
     * otherwise resolve `login` against `https://host/`, producing 404s that look like the server is
     * wrong rather than the URL handling.
     *
     * `ServerUrlNormalizer` produces URLs without a trailing slash, so this is the single place that
     * reconciles the two conventions.
     */
    private fun retrofitFor(baseUrl: String): Retrofit {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
            .build()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
