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
import com.example.shelfplayer.core.model.library.EmbedRequest
import com.example.shelfplayer.core.network.gateway.CoverUpload
import com.example.shelfplayer.core.network.gateway.ProfileConnection
import com.example.shelfplayer.core.network.gateway.ProfileConnectionResolver
import com.example.shelfplayer.core.network.http.NetworkErrorMapper
import com.example.shelfplayer.core.network.http.RetryPolicy
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC MGR-002 / MGR-007 / USER-003 — the three privileged writes, against captured evidence.
 *
 * ### Why this file exists
 *
 * The 2026-08-22 review's third P0: *"Cover upload, metadata embedding, and user activation are live
 * production writes without captured request / response contracts. More broadly, there is no
 * adapter-level `AbsManagementContractTest`; shape and payload tests do not prove Retrofit paths,
 * headers, queries, response decoding, or error mapping."*
 *
 * Both halves were true. The fixtures now exist — `scripts/capture-contracts.sh` drives all three
 * against a real Audiobookshelf 2.36.0 — and this is the adapter test that reads them. It is the sibling
 * of `AbsLibraryContractTest` and `AbsBookmarkContractTest`, and it was the missing one.
 *
 * ### What only a captured fixture could have told us
 *
 * Four things here contradict, or would have been guessed differently from, the reference:
 *
 *  - the cover upload answers **`200 application/json`** with `{"cover": …, "success": true}`, not the
 *    bare `OK` that most of this API's write routes return;
 *  - the embed answers **`200 text/plain "OK"`**, and 200 means *queued* rather than finished;
 *  - the duplicate embed answers `400` as **`text/html`** — `ManagementService`'s KDoc asserted
 *    `text/plain`, "like every other `sendStatus` route on this API", and that was wrong;
 *  - all three refusals answer `403 text/plain "Forbidden"` with no JSON envelope at all, so a client
 *    that parsed an error body would get nothing to show.
 */
class AbsManagementContractTest {

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
    fun tearDown() = server.shutdown()

    private fun api(): AbsManagementApi {
        connections.serverUrl = server.url("/").toString().removeSuffix("/")
        val logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default))
        val services = AudiobookshelfServiceFactory(OkHttpClient(), OkHttpClient(), json)
        return AbsManagementApi(
            services = services,
            connections = connections,
            // The real adapter, not a double: `uploadCover` re-reads the book through it, and that
            // second call is part of the contract this test is here to pin.
            library = AbsLibraryApi(
                services = services,
                connections = connections,
                errors = NetworkErrorMapper(),
                retries = RetryPolicy(logger, Random(seed = 1)),
                clock = TestAppClock(),
                logger = logger,
            ),
            errors = NetworkErrorMapper(),
            logger = logger,
        )
    }

    // --- MGR-002, the upload ------------------------------------------------------------------------

    /**
     * The route, the method, the credential — and the multipart part's **field name**.
     *
     * `cover` is not a guess and not a preference: a part named `image` is refused by this server, which
     * is why `AbsManagementApi` builds the part by hand instead of letting Retrofit name it from the
     * parameter. Asserted from the encoded body because that is the only place the name survives.
     */
    @Test
    fun `uploading a cover posts multipart to the item's cover route`() = runTest {
        server.enqueue(ContractFixtures.response("item-cover-upload"))
        server.enqueue(ContractFixtures.response("library-item"))

        api().uploadCover(PROFILE, BOOK, CoverUpload(bytes = PNG, mimeType = "image/png"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/items/${BOOK.value}/cover", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
        val contentType = request.getHeader("Content-Type").orEmpty()
        assertTrue(contentType.startsWith("multipart/form-data"), "was: $contentType")
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"cover\""), "the part must be named cover, not image")
    }

    /**
     * A successful upload re-reads the book, because the response does not describe it.
     *
     * The captured body carries `{"cover": …, "success": true}` — a path on the server's filesystem and a
     * flag, and nothing a screen can render. The snapshot the caller gets back therefore has to come from
     * a second request, and the second request is what this asserts.
     */
    @Test
    fun `a successful upload returns a freshly read book rather than the upload response`() = runTest {
        server.enqueue(ContractFixtures.response("item-cover-upload"))
        server.enqueue(ContractFixtures.response("library-item"))

        val result = api().uploadCover(PROFILE, BOOK, CoverUpload(bytes = PNG, mimeType = "image/png"))

        assertIs<AppResult.Success<*>>(result)
        server.takeRequest()
        val reread = server.takeRequest()
        val path = reread.path.orEmpty()
        assertEquals("GET", reread.method)
        assertTrue(path.startsWith("/api/items/"), "was: $path")
    }

    /**
     * MGR-002 refused — and the book is **not** re-read.
     *
     * The absent second request is the assertion. A failed upload that still fetched would spend a round
     * trip to redraw a cover that did not change, and on a slow connection would look like it worked.
     */
    @Test
    fun `a refused upload maps to an authorization failure and reads nothing back`() = runTest {
        server.enqueue(ContractFixtures.response("item-cover-upload-forbidden"))

        val result = api().uploadCover(PROFILE, BOOK, CoverUpload(bytes = PNG, mimeType = "image/png"))

        assertIs<AppError.Authorization>(assertIs<AppResult.Failure>(result).error)
        assertEquals(1, server.requestCount, "a refused upload must not re-read the book")
    }

    // --- MGR-007, the embed -------------------------------------------------------------------------

    /**
     * The route, and `backup=1` on the query string.
     *
     * The parameter is the difference between the server keeping a copy of each audio file before
     * rewriting it and not. `ALWAYS_BACK_UP` exists so it cannot be changed without reading why; this
     * asserts it actually reaches the wire, which a default argument alone does not prove.
     */
    @Test
    fun `embedding metadata posts to the tools route with backup enabled`() = runTest {
        server.enqueue(ContractFixtures.response("item-embed-metadata"))

        val result = api().embedMetadata(PROFILE, BOOK)

        assertEquals(AppResult.Success(EmbedRequest.Accepted), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/tools/item/${BOOK.value}/embed-metadata?backup=1", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
    }

    /**
     * **The `400` that is not a failure.**
     *
     * "Already in queue or processing" means the thing the user asked for is happening. Reporting it as
     * an error would be wrong twice: nothing went wrong, and telling somebody their embed failed while it
     * runs invites them to start it again.
     *
     * This is the case the adapter claimed to handle and nothing had ever observed. The captured fixture
     * answers `text/html`, which is worth noting: the code matches on the sentence, so the content type
     * does not affect it — but a future implementation that decided to parse the body by its declared
     * type would break here rather than on somebody's server.
     */
    @Test
    fun `a duplicate embed request is reported as already running, not as an error`() = runTest {
        server.enqueue(ContractFixtures.response("item-embed-metadata-repeated"))

        val result = api().embedMetadata(PROFILE, BOOK)

        assertEquals(AppResult.Success(EmbedRequest.AlreadyRunning), result)
    }

    /** MGR-007 refused on account *type* — a listener holding every grant is still refused. */
    @Test
    fun `a refused embed maps to an authorization failure`() = runTest {
        server.enqueue(ContractFixtures.response("item-embed-metadata-forbidden"))

        val result = api().embedMetadata(PROFILE, BOOK)

        assertIs<AppError.Authorization>(assertIs<AppResult.Failure>(result).error)
    }

    // --- USER-003, activation -----------------------------------------------------------------------

    /**
     * One key on the wire, and only one.
     *
     * The body is asserted **exactly**. `PATCH /api/users/{id}` is a partial update, so every key sent is
     * a key rewritten — an adapter that helpfully included the rest of the user would silently restore
     * permissions an administrator had changed elsewhere, and no response would say so.
     */
    @Test
    fun `deactivating a user patches exactly one field`() = runTest {
        server.enqueue(ContractFixtures.response("user-update-deactivate"))

        val result = api().setUserActive(PROFILE, USER, isActive = false)

        assertEquals(AppResult.Success(Unit), result)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/users/$USER", request.path)
        assertEquals("""{"isActive":false}""", request.body.readUtf8())
    }

    @Test
    fun `reactivating a user sends the opposite value to the same route`() = runTest {
        server.enqueue(ContractFixtures.response("user-update-activate"))

        api().setUserActive(PROFILE, USER, isActive = true)

        assertEquals("""{"isActive":true}""", server.takeRequest().body.readUtf8())
    }

    /**
     * **The response carries a live token, and the app must never read it.**
     *
     * `PATCH /api/users/{id}` answers with the whole user object, and that object contains a `token`
     * field — the same hazard already recorded for `GET /api/users`, now confirmed on this route too.
     * `setUserActive` returns `AppResult<Unit>` and closes the body without parsing it, which is what
     * keeps the credential out of the app entirely.
     *
     * Asserted as an absence, so a future change that started returning the parsed user — reasonable
     * looking, since the server sends one — fails here. The fixture's own token is redacted by the
     * capture, so this test cannot leak one either.
     */
    @Test
    fun `the user update response is never parsed, so its token cannot reach the app`() = runTest {
        server.enqueue(ContractFixtures.response("user-update-deactivate"))

        val result = api().setUserActive(PROFILE, USER, isActive = false)

        assertEquals(AppResult.Success(Unit), result)
        val fixture = ContractFixtures.body("user-update-deactivate")
        assertTrue(fixture.contains("\"token\""), "the fixture must still prove the server sends one")
        assertFalse(sink.text.contains("token", ignoreCase = true), "and nothing about it may be logged")
    }

    /** USER-003 refused. A listener may not change an account, including their own. */
    @Test
    fun `a refused user update maps to an authorization failure`() = runTest {
        server.enqueue(ContractFixtures.response("user-update-forbidden"))

        val result = api().setUserActive(PROFILE, USER, isActive = false)

        assertIs<AppError.Authorization>(assertIs<AppResult.Failure>(result).error)
    }

    private class FakeConnectionResolver : ProfileConnectionResolver {
        var serverUrl: String = ""

        override suspend fun connectionFor(profileId: ProfileId): ProfileConnection? = ProfileConnection(
            profileId = profileId,
            serverId = SERVER,
            serverUrl = serverUrl,
            accessToken = AuthToken(TOKEN),
            access = LibraryAccess(
                hasAllLibraryAccess = true,
                accessibleLibraryIds = emptyList(),
                hasAllTagAccess = true,
            ),
        )
    }

    private companion object {
        val PROFILE = ProfileId("profile-1")
        val SERVER = ServerId("server-1")
        val BOOK = LibraryItemId("book-1")
        const val USER = "user-1"
        const val TOKEN = "contract-token"

        /** A 1×1 PNG. The bytes are irrelevant; that they are sent as a part named `cover` is not. */
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
