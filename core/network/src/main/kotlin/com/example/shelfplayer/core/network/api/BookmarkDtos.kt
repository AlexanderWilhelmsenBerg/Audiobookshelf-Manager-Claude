package com.example.shelfplayer.core.network.api

import kotlinx.serialization.Serializable

/**
 * Wire types for the bookmark routes, verified against Audiobookshelf 2.36.0 on 2026-08-13.
 *
 * PRODUCT_SPEC 9.3: these never leave `:core:network`. The fixtures are `bookmark-create.json`,
 * `me-with-bookmark.json`, `bookmark-update.json` and `bookmark-delete.json`;
 * `docs/api-compatibility.md` records what they settled and `CapturedShapesTest` pins it.
 */

/**
 * What create and update send.
 *
 * `time` is **whole seconds**, because that is what the server keys a bookmark by — the delete route ends
 * in the number. Sending a fractional second would produce a bookmark whose key the app could not
 * reconstruct.
 *
 * No defaults, deliberately, for the reason `PlaySessionRequestDto` gives: `encodeDefaults` is off in this
 * module's `Json`, so a property equal to its default is omitted from the body — and a request shorter
 * than the one the fixtures were captured with is a request no fixture covers.
 */
@Serializable
internal data class BookmarkRequestDto(val time: Long, val title: String)

/**
 * One bookmark, as create, update and `GET /api/me` all report it.
 *
 * Four fields and **no id**. Every one is defaulted, as everywhere in this module: `ignoreUnknownKeys`
 * protects against a server that adds a field, not one that stops sending it, and a missing field has to
 * surface as `AppError.ApiCompatibility` rather than as a deserialization crash.
 *
 * @property time whole seconds from the start of the book. The bookmark's identity.
 * @property createdAt epoch milliseconds, as the server stamps it.
 */
@Serializable
internal data class BookmarkDto(
    val libraryItemId: String? = null,
    val time: Long? = null,
    val title: String? = null,
    val createdAt: Long? = null,
)
