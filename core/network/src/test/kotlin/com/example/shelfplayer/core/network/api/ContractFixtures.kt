package com.example.shelfplayer.core.network.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
        val status = envelope.getValue("status").jsonPrimitive.content.toInt()
        val contentType = envelope["contentType"]?.jsonPrimitive?.contentOrNull() ?: "application/json"
        return MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", contentType)
            .setBody(bodyOf(envelope))
    }

    /** The recorded body as a raw string, for a test that wants to alter one field before serving it. */
    fun body(name: String): String = bodyOf(envelope(name))

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

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? = content.takeIf { it != "null" }
}
