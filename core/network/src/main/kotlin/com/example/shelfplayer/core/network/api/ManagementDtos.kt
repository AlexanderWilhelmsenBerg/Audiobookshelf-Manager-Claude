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
