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
 * Only the one route so far. `POST /api/session/{id}/sync`, `/close`, `/api/session/local` and
 * `/local-all` are captured and belong to wave 3's outbox — declaring them here now would put four
 * unused methods in the way of reading what the app actually sends.
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
}
