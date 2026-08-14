package com.example.shelfplayer.core.network.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Streaming

/**
 * PRODUCT_SPEC DL-001 / DL-002 — the audio file endpoint, verified against Audiobookshelf 2.36.0 on
 * 2026-08-14 and committed as `contracts/item-file.json`.
 *
 * PRODUCT_SPEC 10.4 lists a `DownloadApi` among the sub-APIs and says it arrives "in the phase that
 * implements them, together with the captured fixtures". This is that phase, and this is that fixture:
 * `Accept-Ranges: bytes`, a real `206` with a `Content-Range` for `bytes=0-1023`, an `ETag`, a
 * `Last-Modified`, a `Content-Length`, and `401` for an unauthenticated request.
 *
 * ### `@Streaming`, which is the whole point
 *
 * Without it Retrofit buffers the entire body into memory before returning. An audiobook file is tens or
 * hundreds of megabytes and a book is a dozen of them; on a phone that is an `OutOfMemoryError` rather
 * than a slow download. With it the caller gets a stream and writes as bytes arrive, which is also what
 * makes resume meaningful — there is a partial file on disk to resume *into*.
 *
 * ### The two conditional headers are not optional decoration
 *
 * `Range` asks for the remainder of an interrupted transfer. `If-Range` makes that safe: it tells the
 * server *"only honour that range if the file is still the one whose validator I hold"*. Without it, a
 * file replaced on the server between two attempts produces a `206` splicing the tail of the new file
 * onto the head of the old one — a file that passes every size check and is silently corrupt. With it the
 * server answers a plain `200` and the transfer starts over, which is the only outcome that can be right.
 */
internal interface DownloadService {
    /**
     * One audio file.
     *
     * @param range `bytes=<first>-`, or `null` for the whole file. Open-ended on purpose: the client
     *   knows where it stopped and not where the file ends, and asking for `bytes=n-` is exactly that.
     * @param ifRange the `ETag` recorded when the first part of this file was fetched, or `null` on a
     *   first attempt. Sent **only** alongside [range]; on its own it would turn an ordinary request into
     *   a conditional one and could answer `304` with no body at all.
     */
    @Streaming
    @GET("api/items/{itemId}/file/{fileId}")
    suspend fun file(
        @Header(AUTHORIZATION) bearer: String,
        @Path("itemId") itemId: String,
        @Path("fileId") fileId: String,
        @Header("Range") range: String?,
        @Header("If-Range") ifRange: String?,
    ): Response<ResponseBody>
}
