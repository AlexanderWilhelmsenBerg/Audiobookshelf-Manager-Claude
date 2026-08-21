package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.network.di.AuthenticatedClient
import com.example.shelfplayer.core.network.di.UnauthenticatedClient
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
 * The [OkHttpClient]s are shared across servers on purpose: their connection pools, dispatchers and
 * thread pools are expensive, and Retrofit adds no per-host state that would make sharing unsafe.
 *
 * Which client a service gets is a security decision, not a performance one. [authService] uses the
 * unauthenticated client because its endpoints are addressed at a server the user may not be signed in
 * to and must never receive another profile's ambient token (PRODUCT_SPEC 9.4).
 */
@Singleton
class AudiobookshelfServiceFactory @Inject constructor(
    @param:AuthenticatedClient private val authenticatedClient: OkHttpClient,
    @param:UnauthenticatedClient private val unauthenticatedClient: OkHttpClient,
    private val json: Json,
) {
    internal fun authService(baseUrl: String): AuthService =
        retrofitFor(baseUrl, unauthenticatedClient).create(AuthService::class.java)

    /**
     * The library reads, on the authenticated client.
     *
     * Every call still passes its own `Authorization` header, and `AuthorizationInterceptor` yields to
     * one, so the ambient token is not what authenticates a sync. The authenticated client is used
     * anyway because it is the stack that cover-art loading and media streaming will share — same
     * connection pool, same host — and because a request that somehow arrives without an explicit
     * credential should be signed as the active profile rather than sent bare.
     */
    internal fun libraryService(baseUrl: String): LibraryService =
        retrofitFor(baseUrl, authenticatedClient).create(LibraryService::class.java)

    /**
     * PRODUCT_SPEC PLAY-001 — opening a session, on the same authenticated stack the tracks stream over.
     *
     * Sharing the client with the media data source is the point rather than an incidental saving: the
     * session request and the first track request go to the same host moments apart, so the connection
     * the session was opened over is the one the audio arrives on.
     */
    internal fun playbackService(baseUrl: String): PlaybackService =
        retrofitFor(baseUrl, authenticatedClient).create(PlaybackService::class.java)

    /**
     * PRODUCT_SPEC 11.1 — the bookmark writes, on the authenticated stack like every other write.
     */
    internal fun bookmarkService(baseUrl: String): BookmarkService =
        retrofitFor(baseUrl, authenticatedClient).create(BookmarkService::class.java)

    /**
     * PRODUCT_SPEC DL-001 — the audio file endpoint, on the authenticated stack.
     *
     * The same client the player streams over, deliberately: a download and a stream fetch the same bytes
     * from the same host, and sharing the connection pool means a download that starts while a book is
     * playing reuses the connection rather than opening a second one to the same server.
     */
    internal fun downloadService(baseUrl: String): DownloadService =
        retrofitFor(baseUrl, authenticatedClient).create(DownloadService::class.java)

    /**
     * PRODUCT_SPEC EPIC MGR — the management writes, on the authenticated stack.
     *
     * Same client as the reads, because a save and the refresh that follows it go to the same host in the
     * same second and should share the connection they were both going to open.
     */
    internal fun managementService(baseUrl: String): ManagementService =
        retrofitFor(baseUrl, authenticatedClient).create(ManagementService::class.java)

    /**
     * Retrofit rejects a base URL that does not end in `/`, and — worse — silently discards the last
     * path segment of one that does not. A server behind a reverse proxy at `https://host/abs` would
     * otherwise resolve `login` against `https://host/`, producing 404s that look like the server is
     * wrong rather than the URL handling.
     *
     * `ServerUrlNormalizer` produces URLs without a trailing slash, so this is the single place that
     * reconciles the two conventions.
     */
    private fun retrofitFor(baseUrl: String, client: OkHttpClient): Retrofit {
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
