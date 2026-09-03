package com.example.shelfplayer.core.network.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

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

    /**
     * PRODUCT_SPEC PLAY-004 — "marking finished is explicit". This is the explicit route.
     *
     * Not a session endpoint. A session says "somebody listened from here to here"; this states a fact about
     * the book, which is what the user is doing when they tick or untick *Finished*. The two must not be
     * muddled: marking a book finished from the book screen has to work without opening a session, and
     * un-marking one must not look like listening.
     *
     * `media-progress.json` is the capture. The route and the body shape are exercised by
     * `scripts/capture-contracts.sh`, which writes `{"currentTime":…,"isFinished":…}` and reads the stored
     * progress straight back.
     */
    /**
     * PRODUCT_SPEC SYNC-002 — what the server currently holds for one book.
     *
     * **One request, for one book**, which is the whole reason this exists: the freshness check before an
     * in-app Play used to read `listeningSessions` page by page to exhaustion, because that route is
     * account-wide and has no per-book form. This route does, so the check is a single round trip whose
     * cost does not depend on how much else the account has played.
     *
     * `media-progress.json` is the capture — `scripts/capture-contracts.sh` writes a position with the
     * `PATCH` below and reads it straight back through here. [MediaProgressDto] is the same DTO the account
     * sync reads this object with from `/api/me`; one wire shape, one type.
     */
    @GET("api/me/progress/{itemId}")
    suspend fun mediaProgress(
        @Header(AUTHORIZATION) bearer: String,
        @Path("itemId") itemId: String,
    ): Response<MediaProgressDto>

    @PATCH("api/me/progress/{itemId}")
    suspend fun updateProgress(
        @Header(AUTHORIZATION) bearer: String,
        @Path("itemId") itemId: String,
        @Body request: MediaProgressUpdateDto,
    ): Response<Unit>

    /**
     * PRODUCT_SPEC PLAY-003 — the account's own listening sessions, newest first.
     *
     * `me-listening-sessions.json` is the capture, recorded on a real 2.36.0 immediately after a
     * play/sync/close sequence so it carries real sessions rather than an empty envelope.
     * `docs/api-compatibility.md` records the envelope and — the part a reader guesses wrong — the units:
     * the durations are **seconds** and `startedAt` is **epoch milliseconds**.
     *
     * **Paged, and there is no per-book route.** The server has no
     * `/api/items/{id}/listening-sessions`, so a caller that wants one book's sessions asks for a page of
     * the account's and filters. That is a real cost and it is why this is fetched when the history pane
     * opens rather than on every sync.
     */
    @GET("api/me/listening-sessions")
    suspend fun listeningSessions(
        @Header(AUTHORIZATION) bearer: String,
        @Query("page") page: Int,
        @Query("itemsPerPage") itemsPerPage: Int,
    ): Response<ListeningSessionsResponseDto>
}
