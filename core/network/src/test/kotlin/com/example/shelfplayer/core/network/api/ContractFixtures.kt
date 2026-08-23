package com.example.shelfplayer.core.network.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse

/**
 * PRODUCT_SPEC 22.5 / 17.1 — serves the committed contract fixtures to a MockWebServer.
 *
 * A contract test that hand-writes its own JSON tests the test's idea of the server. These read the
 * envelopes `scripts/capture-contracts.sh` recorded from a real Audiobookshelf 2.36.0, so a shape that
 * drifts on the server side breaks the adapter's tests rather than only the CI drift check.
 *
 * The envelope is what the capture writes: a status, a content type, and either a parsed `body` or a
 * `bodyText` for a response that is not JSON.
 */
internal object ContractFixtures {

    private val json = Json { ignoreUnknownKeys = true }

    /** The recorded response for [name], ready to be enqueued. */
    fun response(name: String): MockResponse {
        val envelope = envelope(name)
        return responseOf(envelope, bodyOf(envelope))
    }

    /**
     * The captured catalogue with deterministic, distinct top-level item ids.
     *
     * Capture redaction deliberately replaces every volatile id with the same literal. That preserves
     * the wire shape, but it cannot model a production catalogue's uniqueness invariant — the invariant
     * used to detect a repeated page before it can authorize deletion. Only the adapter tests restore
     * that lost distinction; the committed evidence remains untouched.
     */
    fun catalogueResponse(): MockResponse {
        val envelope = envelope("library-items")
        val body = envelope.getValue("body").jsonObject
        val results = body.getValue("results").jsonArray.mapIndexed { index, element ->
            JsonObject(element.jsonObject + ("id" to JsonPrimitive("fixture-item-$index")))
        }
        val distinctBody = JsonObject(body + ("results" to JsonArray(results))).toString()
        return responseOf(envelope, distinctBody)
    }

    /**
     * The socket frames a captured envelope holds, in arrival order.
     *
     * For a fixture whose `bodyKind` is `socket-frames` — the capture parses engine.io frames so the JSON
     * inside them can be scrubbed, which also makes them readable as a contract rather than a wall of text.
     * A test that wants one event out of a poll asks for these and picks it, rather than re-implementing the
     * frame split.
     */
    fun frames(name: String): List<JsonObject> = envelope(name)["frames"]?.jsonArray?.map { it.jsonObject }.orEmpty()

    /** The recorded body as a raw string, for a test that wants to alter one field before serving it. */
    fun body(name: String): String = bodyOf(envelope(name))

    /**
     * How many rows a paginated fixture's `results` array holds.
     *
     * So a test can say "one expanded response per catalogue row" instead of a number. The fixture
     * library is not fixed in size — it held one book through Phase 1 and gained a second, multi-file
     * one so PLAY-003's `startOffset` question could be answered — and a test that hard-codes the count
     * fails on a *better* fixture rather than on a regression.
     */
    fun itemCount(name: String): Int = envelope(name)["body"]?.jsonObject?.get("results")?.jsonArray?.size ?: 0

    private fun envelope(name: String): JsonObject {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("contracts/$name.json")) {
            "no committed fixture named contracts/$name.json. Run scripts/capture-contracts.sh."
        }
        return json.parseToJsonElement(stream.use { it.readBytes().decodeToString() }).jsonObject
    }

    private fun bodyOf(envelope: JsonObject): String = when {
        envelope["body"] != null -> envelope.getValue("body").toString()
        envelope["bodyText"] != null -> envelope.getValue("bodyText").jsonPrimitive.content
        else -> ""
    }

    private fun responseOf(envelope: JsonObject, body: String): MockResponse {
        val status = envelope.getValue("status").jsonPrimitive.content.toInt()
        val contentType = envelope["contentType"]?.jsonPrimitive?.contentOrNull() ?: "application/json"
        return MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", contentType)
            .setBody(body)
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? = content.takeIf { it != "null" }
}
