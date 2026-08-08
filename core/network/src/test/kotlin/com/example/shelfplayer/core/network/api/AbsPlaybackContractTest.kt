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
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.OfflineSession
import com.example.shelfplayer.core.model.playback.OfflineSessionResult
import com.example.shelfplayer.core.model.playback.SessionProgress
import com.example.shelfplayer.core.network.gateway.PlaybackDevice
import com.example.shelfplayer.core.network.gateway.ProfileConnection
import com.example.shelfplayer.core.network.gateway.ProfileConnectionResolver
import com.example.shelfplayer.core.network.http.NetworkErrorMapper
import com.example.shelfplayer.core.testing.RecordingLogSink
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-001 / 17.1 — the playback adapter against `item-play.json` and
 * `multi-item-play.json`.
 *
 * Both fixtures came off a real Audiobookshelf 2.36.0. The multi-file one exists specifically to settle
 * `startOffset`, and the test below that reads `6s` off track two is the assertion that keeps wave 2's
 * arithmetic honest: if a future server sent per-file offsets, this fails rather than the position
 * quietly drifting on a device.
 */
class AbsPlaybackContractTest {

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

    private fun api(): AbsPlaybackApi {
        connections.serverUrl = server.url("/").toString().removeSuffix("/")
        return AbsPlaybackApi(
            services = AudiobookshelfServiceFactory(OkHttpClient(), OkHttpClient(), json),
            connections = connections,
            identity = { DEVICE },
            errors = NetworkErrorMapper(),
            logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default)),
        )
    }

    private suspend fun openSession(fixture: String): AppResult<PlaybackSession> {
        server.enqueue(ContractFixtures.response(fixture))
        return api().openSession(PROFILE, BOOK)
    }

    // --- The request -------------------------------------------------------------------------------

    /**
     * The route and the credential.
     *
     * The bearer is asserted because a session opened with the wrong profile's token records one
     * account's listening against another's history (PRODUCT_SPEC 5.2), and the ambient interceptor is
     * not in this test's client — so if the explicit header were dropped, the request would arrive
     * unauthenticated and this would fail.
     */
    @Test
    fun `a session is opened against the item's play route, signed by the named profile`() = runTest {
        openSession("item-play")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/items/${BOOK.value}/play", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
    }

    /**
     * PRODUCT_SPEC 22.5 — the request that produced the fixtures is the request the app sends.
     *
     * The response shape depends on it: `supportedMimeTypes` is what makes the server answer with a
     * direct play rather than a transcoded stream, and a transcoded session is a shape no fixture
     * covers. Pinning the body is what stops a well-meaning edit — adding Opus, say — from silently
     * moving the app onto an unobserved response.
     */
    @Test
    fun `the play request advertises the mime types the fixtures were captured with`() = runTest {
        openSession("item-play")

        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(
            listOf("audio/mpeg", "audio/mp4", "audio/flac"),
            body.getValue("supportedMimeTypes").let { types ->
                types.toString().trim('[', ']').split(',').map { it.trim('"', ' ') }
            },
        )
        assertEquals("exo-player", body.getValue("mediaPlayer").jsonPrimitive.content)
        assertEquals("false", body.getValue("forceDirectPlay").jsonPrimitive.content)
        assertEquals("false", body.getValue("forceTranscode").jsonPrimitive.content)
    }

    /** PRODUCT_SPEC 14.5 — the device description carries no hardware or advertising identifier. */
    @Test
    fun `the device description is what the caller supplied and nothing more`() = runTest {
        openSession("item-play")

        val device = json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject.getValue("deviceInfo").jsonObject
        assertEquals(DEVICE.deviceId, device.getValue("deviceId").jsonPrimitive.content)
        assertEquals(DEVICE.clientName, device.getValue("clientName").jsonPrimitive.content)
        assertEquals(DEVICE.model, device.getValue("model").jsonPrimitive.content)
    }

    // --- The session -------------------------------------------------------------------------------

    /**
     * PRODUCT_SPEC PLAY-001 — the resume position is the server's, not zero.
     *
     * The capture wrote `128.25` with `PATCH /api/me/progress/{id}` moments before opening the session
     * and got it straight back as `startTime`. A player that started at zero would lose the user's place
     * on every book they resumed (product priority 2).
     */
    @Test
    fun `the session resumes where the server says`() = runTest {
        val result = openSession("item-play")

        val session = assertIs<AppResult.Success<PlaybackSession>>(result).value
        assertEquals(128_250L, session.startAt.inWholeMilliseconds)
        assertEquals(8.seconds, session.duration)
        assertEquals("The Salt Harbour", session.title)
        assertEquals("Marisol Holt", session.author)
    }

    /**
     * PRODUCT_SPEC 14.5 — the track URL is absolute, and carries no credential.
     *
     * The server sends a server-relative path with no token in it, which is the good case: the app can
     * fetch it with an `Authorization` header instead of putting a secret in a URL. This pins both
     * halves — that the gateway resolved it against *this* profile's server, and that nothing
     * credential-shaped travelled with it.
     */
    @Test
    fun `track urls are resolved against the profile's server and carry no token`() = runTest {
        val result = openSession("item-play")

        val track = assertIs<AppResult.Success<PlaybackSession>>(result).value.playableTracks.single()
        assertTrue(track.url.startsWith(connections.serverUrl), "resolved against the connection: ${track.url}")
        assertTrue(track.url.contains("/api/items/"), "the path the server gave, kept whole")
        assertFalse(track.url.contains("token", ignoreCase = true), "no credential in a URL")
        assertFalse(track.url.contains("?"), "and no query string to hide one in")
        assertEquals("audio/mpeg", track.mimeType)
    }

    /**
     * PRODUCT_SPEC PLAY-003 — `startOffset` is a **global** offset, and this is the fixture that proves
     * it.
     *
     * Track one is six seconds and track two reports `startOffset: 6`, so the value is the track's start
     * on the book's timeline rather than a per-file zero. Six-then-four rather than two equal files, so
     * "startOffset happens to equal index × duration" cannot pass either.
     */
    @Test
    fun `a multi-file book reports each track's start on the global timeline`() = runTest {
        val result = openSession("multi-item-play")

        val tracks = assertIs<AppResult.Success<PlaybackSession>>(result).value.playableTracks
        assertEquals(listOf(0.seconds, 6.seconds), tracks.map { it.startOffset })
        assertEquals(listOf(6.seconds, 4.seconds), tracks.map { it.duration })
    }

    /** Chapters come off the session, globalised the same way — a chapter can start mid-file. */
    @Test
    fun `chapters are on the book's timeline rather than the file's`() = runTest {
        val result = openSession("multi-item-play")

        val chapters = assertIs<AppResult.Success<PlaybackSession>>(result).value.chapters
        assertEquals(listOf("The Ebb", "The Flood"), chapters.map { it.title })
        assertEquals(listOf(0.seconds, 6.seconds), chapters.map { it.start })
        assertEquals(listOf(6.seconds, 10.seconds), chapters.map { it.end })
        // `dto.id` repeats as 0 for every chapter, so the array position is the identity.
        assertEquals(listOf(0, 1), chapters.map { it.index })
    }

    /** The cover is addressed by the item's cover endpoint; `coverPath` is a server filesystem path. */
    @Test
    fun `the cover is a url rather than the server's filesystem path`() = runTest {
        val result = openSession("item-play")

        val session = assertIs<AppResult.Success<PlaybackSession>>(result).value
        assertEquals("${connections.serverUrl}/api/items/${BOOK.value}/cover", session.coverUrl)
    }

    // --- Failure -----------------------------------------------------------------------------------

    /**
     * PRODUCT_SPEC SYNC-001 — a session with no tracks is a compatibility failure, not an empty player.
     *
     * An ExoPlayer handed an empty playlist reports nothing wrong; it simply never plays. Failing here
     * is what turns that into a message the user can act on.
     */
    @Test
    fun `a session with no playable tracks is reported rather than played`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"s1","audioTracks":[],"chapters":[]}"""))

        val result = api().openSession(PROFILE, BOOK)

        val error = assertIs<AppResult.Failure>(result).error
        assertEquals("audioTracks", assertIs<AppError.ApiCompatibility>(error).missingField)
    }

    /** Every track excluded is the same situation, and the flag must not be quietly ignored. */
    @Test
    fun `a session whose only track is excluded is not played`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"s1","audioTracks":[{"index":1,"contentUrl":"/api/items/b/file/1","exclude":true}]}""",
            ),
        )

        val result = api().openSession(PROFILE, BOOK)

        assertIs<AppResult.Failure>(result)
    }

    /**
     * A profile with no usable connection never reaches the network.
     *
     * `requestCount` is the assertion: an expired session must not produce a request that the server
     * answers with a 401 the user then sees as a server problem.
     */
    @Test
    fun `a profile that cannot connect is an authentication failure`() = runTest {
        connections.hasConnection = false

        val result = api().openSession(PROFILE, BOOK)

        assertIs<AppError.Authentication>(assertIs<AppResult.Failure>(result).error)
        assertEquals(0, server.requestCount)
    }

    /**
     * Opening a session is a write, so it is **not** retried.
     *
     * Every read in this module retries on a 503. This one must not: the server creates a session row
     * per request, so a retry turns one tap into several sessions in the user's own listening history.
     */
    @Test
    fun `a failed open is not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(HTTP_UNAVAILABLE))

        val result = api().openSession(PROFILE, BOOK)

        assertIs<AppResult.Failure>(result)
        assertEquals(1, server.requestCount, "one attempt — a session open is not idempotent")
    }

    /** PRODUCT_SPEC 14.5 — counts and timings are logged; titles, ids and track URLs are not. */
    @Test
    fun `opening a session logs nothing private`() = runTest {
        openSession("item-play")

        val logged = sink.text
        assertFalse(logged.contains("Salt Harbour"), "no media title")
        assertFalse(logged.contains(BOOK.value), "no item id")
        assertFalse(logged.contains("/api/items/"), "no track URL")
        assertTrue(logged.contains("tracks"), "the counts are there: $logged")
    }

    // --- Syncing an open session (PLAY-004) --------------------------------------------------------

    /**
     * PRODUCT_SPEC 22.5 — the sync body is the one the capture sent, in **seconds**.
     *
     * The units are the trap this pins. The models carry `Duration`; the wire carries fractional seconds.
     * A position sent in milliseconds would be read by the server as a position a thousand times further
     * into the book, which on a ten-hour book is "finished" every time (product priority 2).
     */
    @Test
    fun `a session sync sends the position in seconds`() = runTest {
        server.enqueue(ContractFixtures.response("session-sync"))

        val result = api().syncSession(PROFILE, SESSION, progress())

        assertIs<AppResult.Success<Unit>>(result)
        val request = server.takeRequest()
        assertEquals("/api/session/$SESSION/sync", request.path)
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("4.5", body.getValue("currentTime").jsonPrimitive.content)
        assertEquals("3", body.getValue("timeListened").jsonPrimitive.content)
        assertEquals("8.0", body.getValue("duration").jsonPrimitive.content)
    }

    /**
     * The response is `text/plain`, and that must not read as a failure.
     *
     * `session-sync.json` records a `200` whose body is the word `OK`. A method typed to a JSON body
     * would fail to convert it and report a successful sync as an error — which is why the service
     * declares `Response<Unit>`, and why this test serves the recorded response rather than an empty one.
     */
    @Test
    fun `a plain-text OK is a success rather than a parse failure`() = runTest {
        server.enqueue(ContractFixtures.response("session-close"))

        val result = api().closeSession(PROFILE, SESSION, progress())

        assertIs<AppResult.Success<Unit>>(result)
        assertEquals("/api/session/$SESSION/close", server.takeRequest().path)
    }

    @Test
    fun `a rejected sync is reported rather than swallowed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(HTTP_UNAVAILABLE))

        val result = api().syncSession(PROFILE, SESSION, progress())

        assertIs<AppResult.Failure>(result)
    }

    // --- The offline outbox (PLAY-005) -------------------------------------------------------------

    /**
     * PRODUCT_SPEC PLAY-005 — the queued session carries **our** id, and the server's answer is per
     * session.
     *
     * The id is what makes a retry idempotent: the second attempt carries the same one and is recognised
     * as the same session rather than duplicated. A server-generated id could not exist for a session
     * recorded with no network.
     */
    @Test
    fun `an offline session is uploaded under the id this device gave it`() = runTest {
        server.enqueue(ContractFixtures.response("session-local-all"))

        val result = api().syncOfflineSessions(PROFILE, listOf(offlineSession()))

        assertIs<AppResult.Success<List<OfflineSessionResult>>>(result)
        val request = server.takeRequest()
        assertEquals("/api/session/local-all", request.path)
        val sent = json.parseToJsonElement(request.body.readUtf8())
            .jsonObject.getValue("sessions").jsonArray.single().jsonObject
        assertEquals(OFFLINE_ID, sent.getValue("id").jsonPrimitive.content)
        assertEquals("book", sent.getValue("mediaItemType").jsonPrimitive.content)
        // The honest moment the position was recorded. The server resolves conflicts on this value, so a
        // default or an upload-time "now" would let a stale session win.
        assertEquals("1000", sent.getValue("updatedAt").jsonPrimitive.content)
    }

    /**
     * PRODUCT_SPEC PLAY-004 — "accepted" and "progress applied" are different answers.
     *
     * The recorded fixture is the interesting case: `success: true` with `progressSynced: false`, which
     * the capture produced by sending an `updatedAt` older than what the server held. An outbox drains on
     * the first flag; treating the second as a failure would retry a session the server had deliberately
     * declined, forever.
     */
    @Test
    fun `a session the server accepted but whose progress it declined is not a failure`() = runTest {
        server.enqueue(ContractFixtures.response("session-local-all"))

        val result = api().syncOfflineSessions(PROFILE, listOf(offlineSession()))

        val uploaded = assertIs<AppResult.Success<List<OfflineSessionResult>>>(result).value.single()
        assertTrue(uploaded.wasAccepted, "the session was stored")
        assertFalse(uploaded.wasProgressApplied, "and its position was declined, which is the server's rule")
    }

    /** An empty queue is a success with nothing in it, not a request the server has to answer. */
    @Test
    fun `draining an empty outbox makes no request`() = runTest {
        val result = api().syncOfflineSessions(PROFILE, emptyList())

        assertEquals(emptyList(), assertIs<AppResult.Success<List<OfflineSessionResult>>>(result).value)
        assertEquals(0, server.requestCount)
    }

    private fun progress() = SessionProgress(
        position = 4_500.milliseconds,
        duration = 8.seconds,
        timeListened = 3.seconds,
    )

    private fun offlineSession() = OfflineSession(
        id = OFFLINE_ID,
        bookId = BOOK,
        title = "The Salt Harbour",
        author = "Marisol Holt",
        progress = progress(),
        startedAt = Instant.ofEpochMilli(0),
        updatedAt = Instant.ofEpochMilli(1_000),
    )

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
        const val SESSION = "a-server-session-id"
        const val OFFLINE_ID = "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed"
        const val HTTP_UNAVAILABLE = 503
        val PROFILE = ProfileId("profile-1")
        val SERVER = ServerId("server-1")

        /**
         * The fixture's own item id is redacted to `<volatile>`, so the request path is asserted against
         * whatever the test asks for rather than against the recorded value.
         */
        val BOOK = LibraryItemId("book-under-test")

        val DEVICE = PlaybackDevice(
            clientName = "ShelfPlayer",
            clientVersion = "0.0.0",
            deviceId = "a-random-uuid",
            manufacturer = "Test",
            model = "Test Phone",
            sdkVersion = 36,
        )
    }
}
