package com.example.shelfplayer.core.network.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for the authentication endpoints, verified against Audiobookshelf 2.36.0.
 *
 * PRODUCT_SPEC 9.3: these never leave `:core:network`. Every field is either nullable or has a
 * default, because `ignoreUnknownKeys` protects against *added* fields but not against removed ones,
 * and a missing field must surface as [com.example.shelfplayer.core.model.AppError.ApiCompatibility]
 * rather than as a deserialization crash (PRODUCT_SPEC SYNC-001).
 *
 * Shapes here are recorded in `docs/api-compatibility.md` and covered by contract fixtures. They are
 * not in the project's published `openapi.json`, which documents no authentication endpoint at all.
 */
@Serializable
internal data class LoginRequestDto(val username: String, val password: String)

/**
 * `POST /login`.
 *
 * The tokens are nested under `user`, not at the top level — the single most consequential detail of
 * this contract and the easiest one to get wrong from a reference.
 */
@Serializable
internal data class LoginResponseDto(val user: UserDto? = null, val userDefaultLibraryId: String? = null)

@Serializable
internal data class UserDto(
    val id: String? = null,
    val username: String? = null,
    /** `admin`, `user`, `guest`, `root`. */
    val type: String? = null,
    /** Present since 2.26. Absent on older servers, which return only [token]. */
    val accessToken: String? = null,
    /** `null` unless the request carried `x-return-tokens: true`; otherwise set as a cookie. */
    val refreshToken: String? = null,
    /** The pre-2.26 token, still returned alongside [accessToken]. Not refreshable. */
    val token: String? = null,
    val permissions: PermissionsDto? = null,
    val librariesAccessible: List<String> = emptyList(),
    val isActive: Boolean = true,
    val isLocked: Boolean = false,
    /**
     * PRODUCT_SPEC LIB-001 — the positions this account has, as the server has them.
     *
     * Empty in every capture until one was taken against a server that had actually played something;
     * the element shape below is `contracts/media-progress.json`, observed 2026-08-06.
     */
    val mediaProgress: List<MediaProgressDto> = emptyList(),
    /**
     * PRODUCT_SPEC 11.1 — every bookmark this account has, across every book.
     *
     * Flat rather than nested under an item: the server stores bookmarks on the user, so a client that
     * wants one book's filters by `libraryItemId` itself. Present as `[]` in `authorize.json` and
     * `me.json`, and populated in `me-with-bookmark.json`, which is the capture that settled the shape.
     */
    val bookmarks: List<BookmarkDto> = emptyList(),
)

/**
 * PRODUCT_SPEC 5.2 — the server's grant.
 *
 * `accessAllLibraries` is what makes an empty `librariesAccessible` mean "everything" rather than
 * "nothing". Reading the list alone would hide every library from an ordinary admin.
 */
@Serializable
internal data class PermissionsDto(
    @SerialName("accessAllLibraries") val accessAllLibraries: Boolean = false,
    @SerialName("accessAllTags") val accessAllTags: Boolean = false,
    val download: Boolean = false,
    val update: Boolean = false,
    val delete: Boolean = false,
    val upload: Boolean = false,
)

/** `POST /auth/refresh`, which answers with the same envelope as `/login`. */
@Serializable
internal data class RefreshResponseDto(val user: UserDto? = null)

/**
 * `GET /status` — served before authentication.
 *
 * `AUTH-001` uses `app == "audiobookshelf"` to tell an Audiobookshelf server from an arbitrary URL
 * that happens to answer 200, and `SYNC-001` reads [serverVersion] for the capability handshake.
 */
@Serializable
internal data class ServerStatusDto(
    val app: String? = null,
    val serverVersion: String? = null,
    val isInit: Boolean = false,
    val language: String? = null,
    val authMethods: List<String> = emptyList(),
)

/**
 * `GET /api/search/providers` — PRODUCT_SPEC MGR-003.
 *
 * The response nests one level: `{"providers": {"books": [...], "booksCovers": [...], "podcasts": [...]}}`.
 * Only [MetadataProviderListsDto.books] is read. `booksCovers` is the cover-search provider list, which is
 * a different feature, and `podcasts` is for a media type this app does not manage.
 *
 * Every level is nullable with a default, which here is more than the usual defensiveness: this shape is
 * **source-derived and not yet captured**, so a deployment that answers something else must produce an
 * empty list and therefore an unconfirmed capability, never an exception and never an optimistic guess
 * (PRODUCT_SPEC 22.5).
 */
@Serializable
internal data class MetadataProvidersDto(val providers: MetadataProviderListsDto? = null)

@Serializable
internal data class MetadataProviderListsDto(val books: List<MetadataProviderDto> = emptyList())

/**
 * One provider. [value] is what `GET /api/search/books?provider=` takes; [text] is a display name.
 *
 * A provider with no [value] is unusable — there is nothing to send — so it is dropped rather than shown.
 */
@Serializable
internal data class MetadataProviderDto(val value: String? = null, val text: String? = null)
