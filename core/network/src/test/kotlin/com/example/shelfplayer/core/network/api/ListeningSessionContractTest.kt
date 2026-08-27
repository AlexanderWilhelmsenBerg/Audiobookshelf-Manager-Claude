package com.example.shelfplayer.core.network.api

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-003 / 22.5 / 14.5 — `GET /api/me/listening-sessions`, against the captured shape.
 *
 * The fixture is `me-listening-sessions.json`, recorded on 2026-08-27 from a real Audiobookshelf 2.36.0
 * immediately after the play/sync/close sequence, so it carries real sessions rather than an empty
 * envelope. `docs/api-compatibility.md` records what it settled.
 *
 * These parse the committed envelope directly rather than through MockWebServer: what is being pinned is
 * the *shape and its units*, and a transport hop would add nothing to that while making the failure
 * message about a socket.
 */
class ListeningSessionContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun body(): JsonObject = json.parseToJsonElement(ContractFixtures.body("me-listening-sessions")).jsonObject

    private fun sessions(): ListeningSessionsResponseDto =
        json.decodeFromString(ListeningSessionsResponseDto.serializer(), ContractFixtures.body("me-listening-sessions"))

    // ------------------------------------------------------------------ the envelope

    /** The response is paged, and the app has to read `total` rather than the page's size. */
    @Test
    fun `the envelope is paged and reports a total`() {
        val response = sessions()

        assertEquals(0, response.page)
        assertEquals(10, response.itemsPerPage)
        assertEquals(1, response.numPages)
        assertEquals(2, response.total)
        assertEquals(2, response.sessions?.size)
    }

    // ------------------------------------------------------------------ the session

    /**
     * Every field the app reads is present and means what the DTO says.
     *
     * This is the whole point of capturing rather than reading the server's source: the units are the part
     * a reader guesses wrong. `timeListening`, `startTime` and `currentTime` are **seconds**; `startedAt`
     * is **epoch milliseconds**. Getting that pair backwards would put a history row in 1970 or claim a
     * four-second session lasted an hour.
     */
    @Test
    fun `a session carries the fields the history needs`() {
        val session = assertNotNull(sessions().sessions?.firstOrNull())

        assertNotNull(session.id)
        assertNotNull(session.libraryItemId)
        assertEquals("The Salt Harbour", session.displayTitle)
        assertEquals("Marisol Holt", session.displayAuthor)
        assertEquals("book", session.mediaType)
        // Seconds. The seeded book is eight seconds long and four of them were listened to.
        assertEquals(8.0, session.duration)
        assertEquals(4.0, session.timeListening)
        assertEquals(0.0, session.startTime)
        assertEquals(5.5, session.currentTime)
    }

    /** The device half, which is what tells a session from another phone apart from one from this one. */
    @Test
    fun `a session names the device that played it`() {
        val device = assertNotNull(sessions().sessions?.firstOrNull()?.deviceInfo)

        assertNotNull(device.deviceId)
        assertEquals("capture capture", device.deviceName)
        assertEquals("ShelfPlayer", device.clientName)
    }

    /**
     * **The address the response carries and this app must never keep** (PRODUCT_SPEC 14.5).
     *
     * `deviceInfo.ipAddress` is in the wire response — asserted here, so the omission below stays
     * deliberate — and `ListeningSessionDeviceDto` models three fields, none of them that one. It is the
     * same rule that keeps `GET /api/users`' live token out of `UserDto`: the way to be sure a field is
     * never logged is for no type to hold it.
     */
    @Test
    fun `the ip address is in the response and not in the model`() {
        val wire = body().getValue("sessions").jsonArray.first().jsonObject
        val device = wire.getValue("deviceInfo").jsonObject

        assertTrue(device.containsKey("ipAddress"), "the capture must still show the field exists")

        // Asserted against the serializer's own schema rather than a rendered instance. A `toString`
        // scan only proves this *instance* had no address; the descriptor proves the type cannot hold one.
        assertFalse("ipAddress" in fieldsOf(ListeningSessionDeviceDto.serializer().descriptor))
    }

    /**
     * Fields the response carries that the model deliberately drops.
     *
     * A history row is not where a library entry should come from: the app already holds chapters, cover
     * and metadata for a book it knows, and for one it does not, this is the wrong source. Pinned so that
     * dropping them stays a decision.
     */
    @Test
    fun `the redundant halves of the response are not modelled`() {
        val wire = body().getValue("sessions").jsonArray.first().jsonObject

        for (present in listOf("chapters", "mediaMetadata", "coverPath", "bookId")) {
            assertTrue(wire.containsKey(present), "$present should still be in the capture")
        }

        val modelled = fieldsOf(ListeningSessionDto.serializer().descriptor)
        for (dropped in listOf("chapters", "mediaMetadata", "coverPath", "bookId")) {
            assertFalse(dropped in modelled, "$dropped reached the model")
        }
    }

    /** The property names a `@Serializable` type declares, which is its schema. */
    private fun fieldsOf(descriptor: SerialDescriptor): List<String> =
        (0 until descriptor.elementsCount).map(descriptor::getElementName)

    // ------------------------------------------------------------------ through the mapper

    /**
     * **The units, all the way to the domain type** — which is the assertion that actually protects the
     * history pane.
     *
     * The tests above pin what the wire says; this pins that `ListeningSessionMapper` reads it the same way.
     * A mapper that divided by a thousand somewhere, or that treated `startedAt` as seconds, would leave
     * every test above green and put a history row in 1970.
     */
    @Test
    fun `the mapper reads the captured units`() {
        val session = ListeningSessionMapper.toSessions(sessions()).first()
        val wire = body().getValue("sessions").jsonArray.first().jsonObject

        assertEquals(wire.getValue("id").jsonPrimitive.content, session.id)
        assertEquals(wire.getValue("libraryItemId").jsonPrimitive.content, session.bookId.value)
        // Seconds on the wire, a Duration here.
        assertEquals(4.seconds, session.listened)
        assertEquals(Duration.ZERO, session.startedFrom)
        assertEquals(5500.milliseconds, session.reachedAt, "5.5 s, so the rounding is to the millisecond")
        // Epoch milliseconds on the wire — not seconds, which is the mistake this exists to catch.
        assertEquals(
            Instant.ofEpochMilli(wire.getValue("startedAt").jsonPrimitive.long),
            session.startedAt,
        )
    }

    /** The device half survives the mapping, since it is what tells this phone's sessions from another's. */
    @Test
    fun `the mapper keeps the device id and drops nothing else it needs`() {
        val session = ListeningSessionMapper.toSessions(sessions()).first()

        assertNotNull(session.deviceId)
        assertEquals("capture capture", session.deviceName)
        assertEquals("ShelfPlayer", session.clientName)
    }

    /**
     * A session with no id or no item id is **dropped, not failed** — one malformed row must still show the
     * others.
     *
     * Different from `PlaybackMapper.toSession`, which returns `AppError.ApiCompatibility` for the same
     * omission, and deliberately: a session that cannot be synced must not be played, but a history read
     * with one bad row should render the nine good ones.
     */
    @Test
    fun `a session missing its identity is dropped rather than failing the read`() {
        val partial = listOf(
            ListeningSessionDto(id = "s1"),
            ListeningSessionDto(libraryItemId = "b1"),
            ListeningSessionDto(id = "s2", libraryItemId = "b2"),
        )

        val mapped = ListeningSessionMapper.toSessions(ListeningSessionsResponseDto(sessions = partial))

        assertEquals(listOf("s2"), mapped.map { it.id })
    }

    /** An envelope with no `sessions` array at all is an empty list, not a crash — SYNC-001. */
    @Test
    fun `an envelope with no sessions maps to nothing`() {
        assertEquals(emptyList(), ListeningSessionMapper.toSessions(ListeningSessionsResponseDto(total = 0)))
    }

    /** A field the server omits reads back as null rather than throwing — SYNC-001. */
    @Test
    fun `a missing field is tolerated`() {
        val session = json.decodeFromString(ListeningSessionDto.serializer(), """{"id":"s1"}""")

        assertEquals("s1", session.id)
        assertNull(session.libraryItemId)
        assertNull(session.deviceInfo)
        assertNull(session.timeListening)
    }
}
