package com.example.shelfplayer.core.network.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * The playback session endpoints, verified against Audiobookshelf 2.36.0 on 2026-08-07.
 *
 * `item-play.json` and `multi-item-play.json` are what this signature was written against.
 *
 * `/api/session/local` is deliberately **absent**. It is captured, and it is the wrong route for a queue:
 * it answers `200` with an empty body, so a drain cannot tell an accepted session from an ignored one.
 * `local-all` reports per-session results and is used even for a single session.
 */
internal interface PlaybackService {
    /**
     * PRODUCT_SPEC PLAY-001 — opens a session and returns the tracks, chapters and resume position.
     *
     * The credential is an explicit [Header] rather than the interceptor's ambient one, as everywhere
     * in this module: a session is opened *for a profile*, and PRODUCT_SPEC 5.2 does not permit it to
     * be signed with whichever profile happens to be on screen.
     */
    @POST("api/items/{itemId}/play")
    suspend fun play(
        @Header(AUTHORIZATION) bearer: String,
        @Path("itemId") itemId: String,
        @Body request: PlaySessionRequestDto,
    ): Response<PlaybackSessionDto>

    /**
     * PRODUCT_SPEC PLAY-004 — sends a position against an **open** session.
     *
     * `Response<Unit>` because the server answers `200` with `OK` as `text/plain`. Declaring a JSON body
     * would make the converter fail on a successful response.
     */
    @POST("api/session/{sessionId}/sync")
    suspend fun sync(
        @Header(AUTHORIZATION) bearer: String,
        @Path("sessionId") sessionId: String,
        @Body request: SessionSyncRequestDto,
    ): Response<Unit>

    /** PRODUCT_SPEC PLAY-004 — the last position, and the session is done. Same shape as [sync]. */
    @POST("api/session/{sessionId}/close")
    suspend fun close(
        @Header(AUTHORIZATION) bearer: String,
        @Path("sessionId") sessionId: String,
        @Body request: SessionSyncRequestDto,
    ): Response<Unit>

    /**
     * PRODUCT_SPEC PLAY-005 — the outbox drain, with a per-session result.
     *
     * Used for one session as readily as for ten: the single-session route reports nothing a queue can
     * act on. See `LocalSessionResultDto`.
     */
    @POST("api/session/local-all")
    suspend fun syncLocalSessions(
        @Header(AUTHORIZATION) bearer: String,
        @Body request: LocalSessionBatchDto,
    ): Response<LocalSessionBatchResponseDto>
}
