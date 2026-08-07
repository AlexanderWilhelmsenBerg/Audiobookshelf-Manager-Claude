package com.example.shelfplayer.core.network.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC LIB-001 / SYNC-002 / 22.5 — the facts the websocket work is allowed to assume.
 *
 * There is no adapter yet. That is deliberate and is the order PRODUCT_SPEC 22.5 asks for: capture the
 * contract, then write against it. What this file does in the meantime is make the fixtures
 * *load-bearing* — a committed fixture nothing reads is a file somebody will eventually tidy up, and
 * the whole point of these six is that they are the only evidence the project has about a transport it
 * is about to depend on.
 *
 * Each assertion below is a sentence from `docs/api-compatibility.md` turned into a check. If one of
 * them fails, the plan built on it is wrong — which is exactly when a reader wants to be told.
 *
 * Observed against Audiobookshelf 2.36.0 on 2026-08-06 by `scripts/capture-contracts.sh`.
 */
class RealtimeContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * SYNC-001 — the websocket capability is answered by the handshake, not derived from a version.
     *
     * `upgrades` is a property of the *deployment*: a reverse proxy that strips the upgrade does not
     * list it, which is why PRODUCT_SPEC 10.4 wants feature probes rather than version arithmetic.
     */
    @Test
    fun `the handshake offers a websocket upgrade and states its heartbeat`() {
        val payload = framesOf("socket-handshake").single().payloadObject()

        assertEquals(
            listOf("websocket"),
            payload.getValue("upgrades").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(25_000, payload.getValue("pingInterval").jsonPrimitive.content.toInt())
        assertEquals(20_000, payload.getValue("pingTimeout").jsonPrimitive.content.toInt())
    }

    /** engine.io OPEN is frame type `0`; socket.io's namespace CONNECT acknowledgement is `40`. */
    @Test
    fun `the frame types are the engine-io ones the client will have to speak`() {
        assertEquals("0", framesOf("socket-handshake").single().getValue("type").jsonPrimitive.content)
        assertEquals("40", framesOf("socket-connected").single().getValue("type").jsonPrimitive.content)
    }

    /**
     * The authentication event name was a guess when it was sent, and the server accepted it.
     *
     * Pinned here because it is the one part of the sequence that is not documented anywhere: if a
     * future server renames it, this test is the thing that says so rather than a silent connection
     * that authenticates nobody.
     */
    @Test
    fun `authenticating answers user_online and then init`() {
        val events = framesOf("socket-auth").map { it.eventName() }

        assertEquals(listOf("user_online", "init"), events)
    }

    /**
     * PRODUCT_SPEC LIB-001 / 5.2 — the finding that decides the shape of step 10.
     *
     * A progress change made over REST came back as `user_updated` carrying the **whole** user object,
     * not a progress delta. Three acceptance failures are served by that one frame: TC-10 (progress
     * played on another device), TC-37 (a grant changed on the server) and TC-45 (an account disabled
     * server-side). Asserting all four groups of fields together is the point — the plan collapses
     * three handlers into one only for as long as they genuinely arrive together.
     */
    @Test
    fun `a progress change broadcasts the whole user, permissions included`() {
        val frame = framesOf("socket-event-after-progress").single()
        assertEquals("user_updated", frame.eventName())
        val user = frame.eventBody()

        // TC-10: the position itself.
        val progress = user.getValue("mediaProgress").jsonArray.single().jsonObject
        assertEquals("128.25", progress.getValue("currentTime").jsonPrimitive.content)

        // TC-37: the grant, in the same frame.
        assertNotNull(user["permissions"], "a permission change has to be learnable from this event")
        assertNotNull(user["librariesAccessible"])
        assertNotNull(user["itemTagsSelected"])

        // TC-45: whether the account may still be used at all.
        assertNotNull(user["isActive"])
        assertNotNull(user["isLocked"])
    }

    /**
     * PRODUCT_SPEC 14.5 / AUTH-003 — a frame that carries a user carries a token, and these files are
     * committed.
     *
     * The capture redacts it. This asserts the redaction actually happened rather than trusting that it
     * did, because the failure mode is a credential in git history, which cannot be retracted.
     */
    @Test
    fun `no committed realtime fixture carries a credential`() {
        val suspicious = Regex("""eyJ[A-Za-z0-9_-]{10,}\.""")
        REALTIME_FIXTURES.forEach { name ->
            val raw = ContractFixtures.body(name)
            assertTrue(suspicious.findAll(raw).none(), "$name looks like it still holds a JWT")
            assertTrue(
                !raw.contains(""""token":"""") || raw.contains("<redacted-secret>"),
                "$name has an unredacted token field",
            )
        }
    }

    /**
     * PRODUCT_SPEC 13.2 / LIB-001 — the element shape that was `[]` in every earlier capture.
     *
     * Without it a progress-only sync could not be written at all: PRODUCT_SPEC 22.5 forbids mapping a
     * shape no fixture covers, and an empty array covers nothing.
     */
    @Test
    fun `a media progress element carries what a resume needs`() {
        val progress = json.parseToJsonElement(ContractFixtures.body("media-progress")).jsonObject

        listOf(
            "currentTime",
            "duration",
            "progress",
            "isFinished",
            "libraryItemId",
            "mediaItemId",
            "mediaItemType",
            "lastUpdate",
        ).forEach { field ->
            assertNotNull(progress[field], "media progress is missing $field")
        }
    }

    /** `GET /api/me` returns the same object `POST /api/authorize` nests under `user`. */
    @Test
    fun `the me response is the user object the authorize response nests`() {
        val me = json.parseToJsonElement(ContractFixtures.body("me")).jsonObject
        val nested = json.parseToJsonElement(ContractFixtures.body("authorize"))
            .jsonObject.getValue("user").jsonObject

        // Compared by key set rather than by value: the captures were taken at different moments, and
        // the claim being checked is that the two endpoints describe a user the same way.
        assertEquals(nested.keys, me.keys)
    }

    private fun framesOf(fixture: String): List<JsonObject> = json.parseToJsonElement(rawEnvelope(fixture)).jsonObject
        .getValue("frames").jsonArray.map { it.jsonObject }

    private fun rawEnvelope(fixture: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("contracts/$fixture.json")) {
            "no committed fixture named contracts/$fixture.json. Run scripts/capture-contracts.sh."
        }
        return stream.use { it.readBytes().decodeToString() }
    }

    private fun JsonObject.payloadObject(): JsonObject = getValue("payload").jsonObject

    /** A socket.io EVENT frame's payload is `["name", body]`. */
    private fun JsonObject.eventName(): String = getValue("payload").jsonArray.first().jsonPrimitive.content

    private fun JsonObject.eventBody(): JsonObject = getValue("payload").jsonArray[1].jsonObject

    private companion object {
        val REALTIME_FIXTURES = listOf(
            "socket-handshake",
            "socket-connected",
            "socket-auth",
            "socket-event-after-progress",
            "me",
            "media-progress",
        )
    }
}
