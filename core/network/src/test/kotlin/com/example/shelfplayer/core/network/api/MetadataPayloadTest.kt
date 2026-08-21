package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.model.library.BookMetadataEdit
import com.example.shelfplayer.core.model.library.BookMetadataField
import com.example.shelfplayer.core.model.library.SeriesEdit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC MGR-001 — "a save request sends only the server-supported shape".
 *
 * Every assertion here is about a rule observed on Audiobookshelf 2.36.0 and recorded in
 * `docs/api-compatibility.md`. Two of them protect against data loss rather than against a rejected
 * request, and those are the ones worth reading twice.
 */
class MetadataPayloadTest {

    /**
     * **The test that matters most.** `authors` and `series` are replacements on this endpoint: the server
     * removes every entry the array does not contain.
     *
     * So a payload that included them because the form has them — rather than because the user changed
     * them — would delete an author the app's cache happened not to know about. A widened `changed` set is
     * a data-loss bug, not a wasted byte.
     */
    @Test
    fun `an unchanged field is absent, not null`() {
        val body = MetadataPayload.of(form(), setOf(BookMetadataField.Title))

        val metadata = assertIs<JsonObject>(body["metadata"])
        assertEquals(setOf("title"), metadata.keys)
        assertTrue("tags" !in body)
    }

    /** An emptied field is *cleared*, which is a different request from leaving it alone. */
    @Test
    fun `an emptied field is sent as null`() {
        val body = MetadataPayload.of(form().copy(subtitle = ""), setOf(BookMetadataField.Subtitle))

        assertEquals(JsonNull, assertIs<JsonObject>(body["metadata"])["subtitle"])
    }

    /** A year is a string on the wire. The server stores whatever the file's tag contained. */
    @Test
    fun `the published year is a string`() {
        val body = MetadataPayload.of(form(), setOf(BookMetadataField.PublishedYear))

        assertEquals(JsonPrimitive("2024"), assertIs<JsonObject>(body["metadata"])["publishedYear"])
    }

    /** The one field whose position a reader would guess wrong: `tags` is beside `metadata`, not inside it. */
    @Test
    fun `tags sit at the top level`() {
        val body = MetadataPayload.of(form(), setOf(BookMetadataField.Tags))

        assertEquals(JsonArray(listOf(JsonPrimitive("owned"))), body["tags"])
        assertTrue("metadata" !in body)
    }

    /** Authors are objects with a `name`; the server matches by name and ignores any id. */
    @Test
    fun `authors are sent as named objects`() {
        val body = MetadataPayload.of(form(), setOf(BookMetadataField.Authors))

        val authors = assertIs<JsonArray>(assertIs<JsonObject>(body["metadata"])["authors"])
        assertEquals(JsonPrimitive("Marisol Holt"), assertIs<JsonObject>(authors.single())["name"])
    }

    /**
     * The second data-loss guard. A series entry with no name makes the server discard the **entire**
     * array, taking every other series on the book with it — so blank rows never reach the wire.
     */
    @Test
    fun `a blank series row is dropped before it can discard the others`() {
        val withBlank = form().copy(series = form().series + SeriesEdit("", ""))

        val body = MetadataPayload.of(withBlank, setOf(BookMetadataField.Series))

        val series = assertIs<JsonArray>(assertIs<JsonObject>(body["metadata"])["series"])
        assertEquals(1, series.size)
        assertEquals(JsonPrimitive("Harbour Tales"), assertIs<JsonObject>(series.single())["name"])
    }

    /** An emptied sequence is no sequence. Only a string is kept, and `""` is not a position. */
    @Test
    fun `an empty series position is sent as null`() {
        val noSequence = form().copy(series = listOf(SeriesEdit("Harbour Tales", "")))

        val body = MetadataPayload.of(noSequence, setOf(BookMetadataField.Series))

        val series = assertIs<JsonArray>(assertIs<JsonObject>(body["metadata"])["series"])
        assertEquals(JsonNull, assertIs<JsonObject>(series.single())["sequence"])
    }

    @Test
    fun `nothing changed produces an empty body`() {
        assertTrue(MetadataPayload.of(form(), emptySet()).isEmpty())
    }

    private fun form() = BookMetadataEdit(
        title = "The Salt Harbour",
        subtitle = "A subtitle",
        authors = listOf("Marisol Holt"),
        narrators = listOf("Ada Fenwick"),
        series = listOf(SeriesEdit("Harbour Tales", "2")),
        genres = listOf("Fiction"),
        tags = listOf("owned"),
        publishedYear = "2024",
        publisher = "Tidewater",
        description = "",
        isbn = "",
        asin = "",
        language = "",
        isExplicit = false,
        isAbridged = false,
    )
}
