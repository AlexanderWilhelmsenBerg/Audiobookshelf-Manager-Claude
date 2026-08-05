package com.example.shelfplayer.core.network.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The library read endpoints, verified against Audiobookshelf 2.36.0 on 2026-08-05.
 *
 * These *are* in the project's published `openapi.json`, unlike the authentication endpoints, but the
 * captured fixtures are still the authority (PRODUCT_SPEC 22.5, and `23`: the published reference states
 * it is out of date). `libraries.json`, `library-items.json` and `library-item.json` are what these
 * signatures were written against.
 *
 * Every call takes its credential as an explicit [Header] rather than inheriting one from an
 * interceptor. A sync runs for a named profile, and PRODUCT_SPEC 5.2 does not permit it to be signed
 * with whichever profile happens to be on screen.
 */
internal interface LibraryService {
    @GET("api/libraries")
    suspend fun libraries(@Header(AUTHORIZATION) bearer: String): Response<LibrariesResponseDto>

    /**
     * The catalogue of one library.
     *
     * The response is **minified**: counts instead of tracks and chapters, author and series as strings.
     * A book cannot be stored as playable from this alone — see [item].
     */
    @GET("api/libraries/{libraryId}/items")
    suspend fun items(
        @Header(AUTHORIZATION) bearer: String,
        @Path("libraryId") libraryId: String,
    ): Response<LibraryItemsResponseDto>

    /**
     * One item, expanded.
     *
     * `expanded=1` is what adds `media.tracks`, `media.chapters`, `metadata.authors`, `metadata.series`
     * and `metadata.narrators`; `include=progress` adds `userMediaProgress`. PRODUCT_SPEC 2.3 ("offline
     * means genuinely offline") is why the sync pays for one of these per item: a book stored without its
     * track offsets cannot be resumed.
     */
    @GET("api/items/{itemId}")
    suspend fun item(
        @Header(AUTHORIZATION) bearer: String,
        @Path("itemId") itemId: String,
        @Query("expanded") expanded: Int = 1,
        @Query("include") include: String = "progress",
    ): Response<LibraryItemDto>
}
