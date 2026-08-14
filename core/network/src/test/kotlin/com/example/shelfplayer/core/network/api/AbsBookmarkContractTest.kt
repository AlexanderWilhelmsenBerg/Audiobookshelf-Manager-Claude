package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.auth.AuthToken
import com.example.shelfplayer.core.model.auth.LibraryAccess
import com.example.shelfplayer.core.model.library.Bookmark
import com.example.shelfplayer.core.network.gateway.ProfileConnection
import com.example.shelfplayer.core.network.gateway.ProfileConnectionResolver
import com.example.shelfplayer.core.network.http.NetworkErrorMapper
import com.example.shelfplayer.core.testing.RecordingLogSink
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC 11.1 / 17.1 — the bookmark adapter against the four 2026-08-13 fixtures.
 *
 * Three things here would only ever fail against a real server, which is why they are asserted rather
 * than assumed:
 *
 *  - the **delete route carries the position in its path**, in seconds, because that is the bookmark's
 *    only identity;
 *  - the delete's success is `200 text/plain "OK"`, so a JSON body declaration would fail the *happy*
 *    path;
 *  - the create's response position is what gets stored, not the one that was requested — the server is
 *    entitled to have kept something else, and the app has to hold what it can later delete.
 */
class AbsBookmarkContractTest {

    private lateinit var server: MockWebServer
    private val sink = RecordingLogSink()
    private val connections = FakeConnectionResolver()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
        coerceInputValues = false
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(): AbsBookmarkApi {
        connections.serverUrl = server.url("/").toString().removeSuffix("/")
        return AbsBookmarkApi(
            services = AudiobookshelfServiceFactory(OkHttpClient(), OkHttpClient(), json),
            connections = connections,
            errors = NetworkErrorMapper(),
            logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default)),
        )
    }

    // --- Create ------------------------------------------------------------------------------------

    /**
     * The route, the credential, and the body.
     *
     * The bearer is asserted because a bookmark written with the wrong profile's token lands in another
     * account's list (PRODUCT_SPEC 5.2), and the ambient interceptor is not in this test's client — so a
     * dropped explicit header would arrive unauthenticated and this would fail.
     */
    @Test
    fun `creating a bookmark posts seconds and a title to the item's bookmark route`() = runTest {
        server.enqueue(ContractFixtures.response("bookmark-create"))

        api().create(PROFILE, BOOK, at = 31.seconds, title = "A line worth keeping")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/me/item/${BOOK.value}/bookmark", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(31, body.getValue("time").jsonPrimitive.content.toInt())
        assertEquals("A line worth keeping", body.getValue("title").jsonPrimitive.content)
    }

    /** The stored bookmark comes back, and it is the *response's* position that is kept. */
    @Test
    fun `creating a bookmark returns what the server stored`() = runTest {
        server.enqueue(ContractFixtures.response("bookmark-create"))

        val created = api().create(PROFILE, BOOK, at = 99.seconds, title = "ignored")

        val bookmark = assertIs<AppResult.Success<Bookmark>>(created).value
        assertEquals(31.seconds, bookmark.at, "the fixture says 31, and the fixture wins over the request")
        assertEquals("A line worth keeping", bookmark.title)
        assertEquals(BOOK, bookmark.bookId, "the caller's book, not the response's echo")
    }

    /**
     * A response with no position cannot be stored, because there would be nothing to delete later.
     *
     * `ApiCompatibility` rather than a bookmark at zero: a bookmark at the start of the book is a
     * plausible thing to show and a wrong thing to have invented (PRODUCT_SPEC 22.4).
     */
    @Test
    fun `a bookmark response with no position is a compatibility failure`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"libraryItemId":"$BOOK_ID","title":"No time"}"""),
        )

        val created = api().create(PROFILE, BOOK, at = 31.seconds, title = "No time")

        val error = assertIs<AppError.ApiCompatibility>(assertIs<AppResult.Failure>(created).error)
        assertEquals("time", error.missingField)
    }

    // --- Rename ------------------------------------------------------------------------------------

    /** PATCH, and the position travels in the **body** rather than the path. */
    @Test
    fun `renaming a bookmark patches the item's bookmark route`() = runTest {
        server.enqueue(ContractFixtures.response("bookmark-update"))

        val renamed = api().rename(PROFILE, BOOK, at = 31.seconds, title = "A line worth keeping, renamed")

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/me/item/${BOOK.value}/bookmark", request.path)
        assertEquals(
            "A line worth keeping, renamed",
            assertIs<AppResult.Success<Bookmark>>(renamed).value.title,
        )
    }

    // --- Delete ------------------------------------------------------------------------------------

    /**
     * The one that would break in production and pass in a unit test written from memory.
     *
     * The position is in the **path**, in whole seconds. Sending milliseconds would delete nothing and
     * report success, because the server answers `200` either way.
     */
    @Test
    fun `deleting a bookmark addresses its position in seconds`() = runTest {
        server.enqueue(ContractFixtures.response("bookmark-delete"))

        val removed = api().remove(PROFILE, BOOK, at = 31.seconds)

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/me/item/${BOOK.value}/bookmark/31", request.path)
        assertIs<AppResult.Success<Unit>>(removed)
    }

    /** `200 text/plain OK` is a success. A JSON body declaration would have made it a parse failure. */
    @Test
    fun `a plain text OK is a successful delete`() = runTest {
        server.enqueue(ContractFixtures.response("bookmark-delete"))

        assertIs<AppResult.Success<Unit>>(api().remove(PROFILE, BOOK, at = 31.seconds))
    }

    // --- Failures ----------------------------------------------------------------------------------

    /** PRODUCT_SPEC 5.2 — no connection for the profile is an authentication failure, not a crash. */
    @Test
    fun `a profile with no connection cannot write a bookmark`() = runTest {
        connections.hasConnection = false

        val created = api().create(PROFILE, BOOK, at = 31.seconds, title = "nowhere to go")

        assertIs<AppError.Authentication>(assertIs<AppResult.Failure>(created).error)
        assertEquals(0, server.requestCount, "and nothing was sent")
    }

    @Test
    fun `a rejected bookmark maps its status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val created = api().create(PROFILE, BOOK, at = 31.seconds, title = "not allowed")

        assertIs<AppError.Authorization>(assertIs<AppResult.Failure>(created).error)
    }

    /**
     * PRODUCT_SPEC 14.5 — a bookmark's title is the listener's own words about a book, and never logged.
     *
     * The position is, because "the write at 31 s failed" is what a support report needs and a number of
     * seconds names nothing.
     */
    @Test
    fun `no log line carries the bookmark's title or its book`() = runTest {
        server.enqueue(ContractFixtures.response("bookmark-create"))

        api().create(PROFILE, BOOK, at = 31.seconds, title = "The bit about the harbour")

        assertFalse(sink.text.contains("harbour", ignoreCase = true), "the title is private: ${sink.text}")
        assertFalse(sink.text.contains(BOOK_ID), "and so is the book")
        assertTrue(sink.text.contains("bookmark"), "but the event itself is recorded: ${sink.text}")
    }

    private class FakeConnectionResolver : ProfileConnectionResolver {
        var serverUrl: String = ""
        var hasConnection: Boolean = true

        override suspend fun connectionFor(profileId: ProfileId): ProfileConnection? {
            if (!hasConnection) return null
            return ProfileConnection(
                profileId = profileId,
                serverId = SERVER,
                serverUrl = serverUrl,
                accessToken = AuthToken(TOKEN),
                access = LibraryAccess(hasAllLibraryAccess = true, accessibleLibraryIds = emptyList()),
            )
        }
    }

    private companion object {
        const val TOKEN = "that-profiles-token"
        const val BOOK_ID = "book-salt-harbour"
        val SERVER = ServerId("server-1")
        val PROFILE = ProfileId("profile-1")
        val BOOK = LibraryItemId(BOOK_ID)
    }
}
