package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.model.library.BookMetadataEdit
import com.example.shelfplayer.core.model.library.BookMetadataField
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `PATCH /api/items/{id}/media`, captured as `item-update.json`.
 *
 * [updated] is `false` when the request changed nothing — every value it carried already matched. That is
 * a success, not a failure, and it is the answer to a save the user made after undoing their own edit.
 */
@Serializable
internal data class MediaUpdateResponseDto(val updated: Boolean = false, val libraryItem: LibraryItemDto? = null)

/**
 * `POST /api/items/{id}/cover`.
 *
 * [cover] is a path on the **server's** filesystem, not a URL. It is read only to confirm the server
 * accepted something; the app never uses it to address an image (PRODUCT_SPEC 3.4).
 */
@Serializable
internal data class CoverUploadResponseDto(val success: Boolean = false, val cover: String? = null)

/**
 * `GET /api/users`.
 *
 * The envelope. See [UserSummaryDto] for the field this deliberately does not have.
 */
@Serializable
internal data class UsersResponseDto(val users: List<UserSummaryDto> = emptyList())

/**
 * One account, as an administrator may see it.
 *
 * ### The missing field is the point
 *
 * The server sends a `token` for every user in this list — a live access token, for accounts other than the
 * caller's. There is no property for it here, and adding one would be a defect rather than a feature:
 * PRODUCT_SPEC USER-001 forbids displaying tokens, and the only way to guarantee that across a codebase is
 * for the value never to be parsed. `pash`, the password hash, is absent for the same reason.
 *
 * `CapturedShapesTest` asserts the fixture *does* contain the token, so this omission stays deliberate
 * rather than becoming an oversight somebody "fixes".
 */
@Serializable
internal data class UserSummaryDto(
    val id: String? = null,
    val username: String? = null,
    val type: String? = null,
    val isActive: Boolean = false,
    val isLocked: Boolean = false,
    val permissions: UserPermissionsDto? = null,
    val librariesAccessible: List<String> = emptyList(),
)

@Serializable
internal data class UserPermissionsDto(
    val download: Boolean = false,
    val update: Boolean = false,
    val delete: Boolean = false,
    val upload: Boolean = false,
    val accessAllLibraries: Boolean = false,
)

/**
 * `POST /api/users`.
 *
 * [isActive] is not optional in practice: the server reads `!!req.body.isActive`, so omitting it creates an
 * account nobody can sign in to.
 */
@Serializable
internal data class CreateUserRequestDto(
    val username: String,
    val password: String,
    val type: String,
    val isActive: Boolean,
    val permissions: CreateUserPermissionsDto,
)

@Serializable
internal data class CreateUserPermissionsDto(
    val download: Boolean,
    val update: Boolean,
    val delete: Boolean,
    val upload: Boolean,
)

/** `POST /api/users` — the created account, in the same shape as a listed one, token included and dropped. */
@Serializable
internal data class CreateUserResponseDto(val user: UserSummaryDto? = null)

/**
 * `GET /api/search/books` — one candidate from a metadata provider.
 *
 * **Every field is optional, including the ones that look mandatory.** The shape varies by provider:
 * Google returns ten of these, Audible returns all of them plus a narrator and a duration, and a custom
 * provider returns whatever its author chose. A required field here would turn one provider's omission
 * into a failed search.
 *
 * Two of these are hazards rather than data. [cover] is a URL on a third party's host — MGR-002's "tokens
 * are not appended to third-party cover URLs" is about exactly this value. [description] is
 * provider-supplied HTML, which is what MGR-003 means by "match results are treated as untrusted display
 * data and sanitized".
 */
@Serializable
internal data class MatchCandidateDto(
    val title: String? = null,
    val subtitle: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    val publisher: String? = null,
    val publishedYear: String? = null,
    val description: String? = null,
    val cover: String? = null,
    val isbn: String? = null,
    val asin: String? = null,
    val genres: List<String>? = null,
    val tags: List<String>? = null,
    val language: String? = null,
    val series: List<MatchSeriesDto>? = null,
)

@Serializable
internal data class MatchSeriesDto(val series: String? = null, val sequence: String? = null)

/**
 * `POST /api/items/{id}/scan` — the conclusion, as a name.
 *
 * One of `NOTHING`, `ADDED`, `UPDATED`, `REMOVED`, `UPTODATE`. Kept as the server's own string rather than
 * mapped to an enum here: a value this build has never seen must survive to the log, and a `when` that had
 * to be exhaustive would be a guess about a future server (PRODUCT_SPEC 22.4).
 */
@Serializable
internal data class ScanResultDto(val result: String? = null)

/**
 * PRODUCT_SPEC MGR-001 — "a save request sends only the server-supported shape".
 *
 * The single place a metadata `PATCH` body is constructed, and the reason [ManagementService.updateMedia]
 * takes a raw [JsonObject]: this has to be able to say *absent*, *null* and *empty* as three different
 * things, which a serializable data class cannot.
 *
 * ### The rules this encodes, all of them observed
 *
 * - **Only changed fields are sent.** Not an optimisation. `authors` and `series` are replacements, so an
 *   unchanged array included "for completeness" would delete whatever the app's cache did not know about.
 * - **An emptied text field is sent as `null`**, which is what clears it. `""` would work too — the server
 *   coerces it — but `null` says what is meant.
 * - **`publishedYear` is a string on the wire** even though it is a year. The server stores whatever the
 *   file's tag contained, and only coerces numbers to strings.
 * - **`tags` sits at the top level**, beside `metadata` rather than inside it. The one field whose position
 *   a reader would guess wrong.
 * - **A series entry needs a name.** The server discards the entire array if any entry lacks one, so a
 *   blank row would take every other series with it — [BookMetadataEdit.normalized] drops them first.
 */
internal object MetadataPayload {

    fun of(edit: BookMetadataEdit, changed: Set<BookMetadataField>): JsonObject {
        val form = edit.normalized()
        val metadata = metadataOf(form, changed)
        return buildJsonObject {
            if (metadata.isNotEmpty()) put(METADATA, metadata)
            if (BookMetadataField.Tags in changed) {
                putJsonArray(TAGS) { form.tags.forEach { tag -> add(tag) } }
            }
        }
    }

    private fun metadataOf(form: BookMetadataEdit, changed: Set<BookMetadataField>): JsonObject = buildJsonObject {
        text(changed, BookMetadataField.Title, "title", form.title)
        text(changed, BookMetadataField.Subtitle, "subtitle", form.subtitle)
        text(changed, BookMetadataField.PublishedYear, "publishedYear", form.publishedYear)
        text(changed, BookMetadataField.Publisher, "publisher", form.publisher)
        text(changed, BookMetadataField.Description, "description", form.description)
        text(changed, BookMetadataField.Isbn, "isbn", form.isbn)
        text(changed, BookMetadataField.Asin, "asin", form.asin)
        text(changed, BookMetadataField.Language, "language", form.language)
        if (BookMetadataField.Explicit in changed) put("explicit", form.isExplicit)
        if (BookMetadataField.Abridged in changed) put("abridged", form.isAbridged)
        if (BookMetadataField.Narrators in changed) {
            putJsonArray("narrators") { form.narrators.forEach { narrator -> add(narrator) } }
        }
        if (BookMetadataField.Genres in changed) {
            putJsonArray("genres") { form.genres.forEach { genre -> add(genre) } }
        }
        if (BookMetadataField.Authors in changed) {
            // Objects rather than strings: the server reads `.name` off each element and ignores any id,
            // matching by name to decide what to keep, add and remove.
            putJsonArray("authors") {
                form.authors.forEach { name -> add(buildJsonObject { put("name", name) }) }
            }
        }
        if (BookMetadataField.Series in changed) {
            putJsonArray("series") {
                form.series.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("name", entry.name)
                            // Only a *string* sequence is kept; anything else is stored as no sequence,
                            // which is what an emptied field should mean anyway.
                            put("sequence", entry.sequence.orNull())
                        },
                    )
                }
            }
        }
    }

    private fun JsonObjectBuilder.text(
        changed: Set<BookMetadataField>,
        field: BookMetadataField,
        key: String,
        value: String,
    ) {
        if (field !in changed) return
        put(key, value.orNull())
    }

    private fun String.orNull() = if (isEmpty()) JsonNull else JsonPrimitive(this)

    private const val METADATA = "metadata"
    private const val TAGS = "tags"
}
