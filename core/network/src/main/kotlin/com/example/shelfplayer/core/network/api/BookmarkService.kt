package com.example.shelfplayer.core.network.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * PRODUCT_SPEC 11.1 — the bookmark endpoints, verified against Audiobookshelf 2.36.0 on 2026-08-13.
 *
 * Three routes rather than four: **there is no read route.** Bookmarks live on the user, so
 * `GET /api/me` returns every one of them across every book, and that call already exists on
 * [AuthService] and is already made on every profile refresh. Adding a second way to read them would be
 * a second thing to keep in step for no gain.
 *
 * The credential is an explicit [Header] rather than the interceptor's ambient one, as everywhere in this
 * module: a bookmark belongs *to a profile*, and PRODUCT_SPEC 5.2 does not permit it to be written with
 * whichever profile happens to be on screen.
 */
internal interface BookmarkService {
    /**
     * Creates a bookmark and returns it.
     *
     * `bookmark-create.json`. The response is the bookmark rather than a status, so the caller can render
     * what was actually stored — which matters here, because the server may have truncated the position
     * or replaced an existing bookmark at the same second.
     */
    @POST("api/me/item/{itemId}/bookmark")
    suspend fun create(
        @Header(AUTHORIZATION) bearer: String,
        @Path("itemId") itemId: String,
        @Body request: BookmarkRequestDto,
    ): Response<BookmarkDto>

    /**
     * Renames the bookmark at `request.time`. `bookmark-update.json`.
     *
     * The position is in the **body**, not the path, and it is not changeable: a bookmark *is* its
     * position, so moving one means deleting and creating. The app does not offer that, because a
     * listener who wants a different position wants a different bookmark.
     */
    @PATCH("api/me/item/{itemId}/bookmark")
    suspend fun update(
        @Header(AUTHORIZATION) bearer: String,
        @Path("itemId") itemId: String,
        @Body request: BookmarkRequestDto,
    ): Response<BookmarkDto>

    /**
     * Deletes the bookmark at [timeSeconds]. `bookmark-delete.json`.
     *
     * `Response<Unit>` because the server answers `200` with `OK` as `text/plain` — not JSON, and not
     * `204`. Declaring a JSON body would make the converter fail on the *successful* response, which is
     * the kind of defect that only appears the first time a user deletes something.
     */
    @DELETE("api/me/item/{itemId}/bookmark/{timeSeconds}")
    suspend fun delete(
        @Header(AUTHORIZATION) bearer: String,
        @Path("itemId") itemId: String,
        @Path("timeSeconds") timeSeconds: Long,
    ): Response<Unit>
}
