package com.example.shelfplayer.core.network.api

import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.Path

/**
 * PRODUCT_SPEC EPIC MGR — the endpoints that write to somebody else's library.
 *
 * Separate from [LibraryService] on purpose. Everything there reads; everything here changes a server the
 * user shares with their household, and keeping the two apart means a reviewer can see the whole write
 * surface of this app in one file.
 *
 * Shapes are recorded in `docs/api-compatibility.md` and captured by `scripts/capture-contracts.sh`.
 */
internal interface ManagementService {

    /**
     * PRODUCT_SPEC MGR-001 — the metadata edit.
     *
     * ### Why the body is a [JsonObject] rather than a data class
     *
     * The server treats an **absent** key and a `null` key differently, and this is the one place in the
     * app where that distinction carries the whole feature. A key that is absent is left alone; a key that
     * is present and empty is *cleared*. `@Serializable` with nullable fields cannot express both — it has
     * one way to say "no value" and the wire needs two.
     *
     * More sharply: `metadata.authors` and `metadata.series` are **replacements, not additions**. Sending
     * either one makes the server remove every entry not in the array. So a payload built from anything
     * other than exactly the fields the user changed would silently delete data — send an `authors` array
     * because the form has one, and a book whose authors were never touched loses the ones the request
     * happened not to know about.
     *
     * `MetadataPayload` builds the object from the dirty set, and nothing else may construct one.
     *
     * The response is the whole updated item, which is what MGR-001's *"refreshes from server"* is: a
     * follow-up `GET` would ask for data already in hand and open a window for the two to disagree.
     */
    @PATCH("api/items/{itemId}/media")
    suspend fun updateMedia(
        @Header(AUTHORIZATION) bearer: String,
        @Path("itemId") itemId: String,
        @Body body: JsonObject,
    ): Response<MediaUpdateResponseDto>
}
