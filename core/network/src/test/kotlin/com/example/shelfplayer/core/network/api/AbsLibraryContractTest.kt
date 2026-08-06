package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.auth.AuthToken
import com.example.shelfplayer.core.model.auth.LibraryAccess
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibrarySnapshot
import com.example.shelfplayer.core.network.gateway.ProfileConnection
import com.example.shelfplayer.core.network.gateway.ProfileConnectionResolver
import com.example.shelfplayer.core.network.http.NetworkErrorMapper
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC LIB-001 / 17.1 — the library adapter against the captured fixtures.
 *
 * `libraries.json`, `library-items.json` and `library-item.json` were recorded from a real Audiobookshelf
 * 2.36.0 with one scanned audiobook, so every shape here is one the server actually produced. The two
 * properties the class exists for — an unauthorized library never leaves the gateway, and a partial
 * library is never reported as complete — are tested by making the server behave badly, not by inspecting
 * the code.
 */
class AbsLibraryContractTest {

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

    private fun api(): AbsLibraryApi {
        connections.serverUrl = server.url("/").toString().removeSuffix("/")
        return AbsLibraryApi(
            services = AudiobookshelfServiceFactory(OkHttpClient(), OkHttpClient(), json),
            connections = connections,
            errors = NetworkErrorMapper(),
            clock = TestAppClock(),
            logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default)),
        )
    }

    // --- GET /api/libraries ------------------------------------------------------------------------

    @Test
    fun `libraries are read from the captured envelope`() = runTest {
        server.enqueue(ContractFixtures.response("libraries"))

        val libraries = assertIs<AppResult.Success<List<Library>>>(api().listLibraries(PROFILE)).value

        val library = libraries.single()
        assertEquals("Contract Fiction", library.name)
        assertEquals(SERVER, library.serverId)
        assertEquals(com.example.shelfplayer.core.model.library.LibraryKind.Book, library.kind)

        val request = server.takeRequest()
        assertEquals("/api/libraries", request.path)
        assertEquals("Bearer that-profiles-token", request.getHeader("Authorization"))
    }

    /**
     * The Phase 1 exit criterion, at the only layer where it can be guaranteed: an ungranted library does
     * not leave the gateway, so no repository can write it and no UI has to remember to hide it.
     */
    @Test
    fun `a library the profile is not granted is never returned`() = runTest {
        connections.access = LibraryAccess(hasAllLibraryAccess = false, accessibleLibraryIds = emptyList())
        server.enqueue(ContractFixtures.response("libraries"))

        val libraries = assertIs<AppResult.Success<List<Library>>>(api().listLibraries(PROFILE)).value

        assertTrue(libraries.isEmpty())
    }

    @Test
    fun `a granted library is returned while an ungranted one beside it is not`() = runTest {
        connections.access = LibraryAccess(
            hasAllLibraryAccess = false,
            accessibleLibraryIds = listOf(LibraryId("lib-granted")),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {"libraries":[
                  {"id":"lib-granted","name":"Granted","mediaType":"book","displayOrder":1},
                  {"id":"lib-withheld","name":"Withheld","mediaType":"book","displayOrder":2}
                ]}
                """.trimIndent(),
            ),
        )

        val libraries = assertIs<AppResult.Success<List<Library>>>(api().listLibraries(PROFILE)).value

        assertEquals(listOf("lib-granted"), libraries.map { it.id.value })
    }

    /** PRODUCT_SPEC 5.2 — the empty-list trap: `accessAllLibraries` with no list means everything. */
    @Test
    fun `an account granted all libraries sees one the list does not name`() = runTest {
        connections.access = LibraryAccess(hasAllLibraryAccess = true, accessibleLibraryIds = emptyList())
        server.enqueue(ContractFixtures.response("libraries"))

        assertEquals(1, assertIs<AppResult.Success<List<Library>>>(api().listLibraries(PROFILE)).value.size)
    }

    /** PRODUCT_SPEC 14.5 — a withheld library's name must not reach the log that records the filtering. */
    @Test
    fun `filtering logs a count, not a library name`() = runTest {
        connections.access = LibraryAccess(hasAllLibraryAccess = false, accessibleLibraryIds = emptyList())
        server.enqueue(ContractFixtures.response("libraries"))

        api().listLibraries(PROFILE)

        assertTrue(sink.text.contains("withheld"))
        assertFalse(sink.text.contains("Contract Fiction"))
    }

    /**
     * A profile with no usable session is an authentication failure, not an empty library. An empty list
     * renders as "you have no books" when the truth is "you need to sign in" (PRODUCT_SPEC 14.4).
     */
    @Test
    fun `a profile with no connection reports an authentication failure`() = runTest {
        connections.hasConnection = false

        val error = assertIs<AppResult.Failure>(api().listLibraries(PROFILE)).error

        assertIs<AppError.Authentication>(error)
    }

    // --- GET /api/libraries/{id}/items and the expanded item ---------------------------------------

    /**
     * The N+1 the minified list forces, and the reason for it: the snapshot has tracks and chapters, which
     * the list response does not carry at all.
     */
    @Test
    fun `each item is fetched expanded so the snapshot has tracks and chapters`() = runTest {
        server.enqueue(ContractFixtures.response("library-items"))
        server.enqueue(ContractFixtures.response("library-item"))

        val books = assertIs<AppResult.Success<LibrarySnapshot>>(api().listBooks(PROFILE, LIBRARY)).value.books

        val snapshot = books.single()
        assertEquals("The Salt Harbour", snapshot.book.title)
        assertEquals(listOf("Marisol Holt"), snapshot.book.authors.map { it.name })
        assertEquals(listOf("Ada Fenwick"), snapshot.book.narrators)
        assertEquals(2024, snapshot.book.publishedYear)
        assertEquals(listOf("Fiction"), snapshot.book.genres)
        assertEquals(8_000L, snapshot.book.duration.inWholeMilliseconds)
        assertEquals(1, snapshot.book.trackCount)

        // PRODUCT_SPEC 11.3 — the server's own offsets, not offsets derived by summing durations.
        val track = snapshot.tracks.single()
        assertEquals(0L, track.startOffset.inWholeMilliseconds)
        assertEquals(8_000L, track.duration.inWholeMilliseconds)
        assertEquals("audio/mpeg", track.mimeType)

        assertEquals(listOf("Chapter One", "Chapter Two"), snapshot.chapters.map { it.title })
        assertEquals(4_000L, snapshot.chapters[1].start.inWholeMilliseconds)

        val list = server.takeRequest()
        assertEquals("/api/libraries/lib-1/items", list.path)
        val expanded = server.takeRequest()
        assertTrue(expanded.path!!.startsWith("/api/items/"))
        assertTrue(expanded.path!!.contains("expanded=1"))
        assertTrue(expanded.path!!.contains("include=progress"))
    }

    /**
     * PRODUCT_SPEC 15 — "deep links validate server/profile/item access".
     *
     * A library id can arrive from somewhere other than a listing, so the grant is checked again here and
     * no request is made at all for one the profile is not granted.
     */
    @Test
    fun `books cannot be fetched for a library the profile is not granted`() = runTest {
        connections.access = LibraryAccess(hasAllLibraryAccess = false, accessibleLibraryIds = emptyList())

        val error = assertIs<AppResult.Failure>(api().listBooks(PROFILE, LIBRARY)).error

        assertIs<AppError.Authorization>(error)
        assertEquals(0, server.requestCount, "no request may be made for an ungranted library")
    }

    /**
     * PRODUCT_SPEC LIB-001 — when *every* item fails, the sync failed.
     *
     * The distinction matters: a library that returned nothing because the server stopped answering must
     * not read as a library with no books in it, which is what a `Success(emptyList())` would render as.
     */
    @Test
    fun `a library whose every item fails is a failure, not an empty library`() = runTest {
        server.enqueue(ContractFixtures.response("library-items"))
        server.enqueue(MockResponse().setResponseCode(503))

        val error = assertIs<AppResult.Failure>(api().listBooks(PROFILE, LIBRARY)).error

        assertTrue(error.isRetryable)
    }

    /**
     * PRODUCT_SPEC LIB-001 — "failed optional sections do not fail the whole sync".
     *
     * This is the defect three device runs reported as "the library was empty until I pressed refresh". A
     * first sync of a real library is one request per item, and this code used to abandon every snapshot it
     * had already fetched the moment one of them failed. On a 490-book library that is 490 chances to throw
     * the whole thing away.
     *
     * The unreachable count rides along because the caller must not treat those items as deleted: absence
     * caused by a dropped connection says nothing about whether the book still exists (product priority 2).
     */
    @Test
    fun `one failed item keeps the rest of the library and reports it as incomplete`() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[{"id":"item-1"},{"id":"item-2"}],"total":2}"""))
        server.enqueue(ContractFixtures.response("library-item"))
        server.enqueue(MockResponse().setResponseCode(503))

        val snapshot = assertIs<AppResult.Success<LibrarySnapshot>>(api().listBooks(PROFILE, LIBRARY)).value

        assertEquals(1, snapshot.books.size)
        assertEquals(1, snapshot.unreachableCount)
        assertFalse(snapshot.isComplete, "an incomplete fetch must not be read as a complete one")
    }

    /**
     * A `404` is different in kind: the item is gone, which is a fact rather than a failure. Omitting it
     * lets the repository soft-delete it correctly — and the fetch is still *complete*, which is what
     * gives the repository permission to.
     */
    @Test
    fun `an item that vanished between the listing and its fetch is omitted, not fatal`() = runTest {
        server.enqueue(ContractFixtures.response("library-items"))
        server.enqueue(MockResponse().setResponseCode(404))

        val snapshot = assertIs<AppResult.Success<LibrarySnapshot>>(api().listBooks(PROFILE, LIBRARY)).value

        assertTrue(snapshot.books.isEmpty())
        assertEquals(1, snapshot.removedCount)
        assertTrue(snapshot.isComplete, "a removal is an answer, so the fetch saw everything")
    }

    /** PRODUCT_SPEC SYNC-001 — a minified item cannot be stored, and says which field is missing. */
    @Test
    fun `an item without tracks is a compatibility error naming the field`() = runTest {
        server.enqueue(ContractFixtures.response("library-items"))
        server.enqueue(
            MockResponse().setBody(
                """{"id":"item-1","media":{"metadata":{"title":"A Book"},"numTracks":3}}""",
            ),
        )

        val error = assertIs<AppResult.Failure>(api().listBooks(PROFILE, LIBRARY)).error

        assertEquals("media.tracks", assertIs<AppError.ApiCompatibility>(error).missingField)
    }

    @Test
    fun `an empty library is an empty list, not a failure`() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[],"total":0}"""))

        val books = assertIs<AppResult.Success<LibrarySnapshot>>(api().listBooks(PROFILE, LIBRARY)).value.books

        assertTrue(books.isEmpty())
    }

    /** The captured fixture has no progress, and its absence must be `null` rather than a zero position. */
    @Test
    fun `an item with no server progress carries none`() = runTest {
        server.enqueue(ContractFixtures.response("library-items"))
        server.enqueue(ContractFixtures.response("library-item"))

        val books = assertIs<AppResult.Success<LibrarySnapshot>>(api().listBooks(PROFILE, LIBRARY)).value.books

        assertNull(books.single().book.progress)
    }

    /** PRODUCT_SPEC 5.2 — server progress belongs to the profile whose credential fetched it. */
    @Test
    fun `server progress is scoped to the requesting profile`() = runTest {
        server.enqueue(ContractFixtures.response("library-items"))
        server.enqueue(
            MockResponse().setBody(
                """
                {"id":"item-1","media":{"metadata":{"title":"A Book"},"duration":8,
                "tracks":[{"index":1,"startOffset":0,"duration":8,"contentUrl":"/api/items/x/file/1"}],
                "chapters":[]},
                "userMediaProgress":{"currentTime":4.5,"duration":8,"isFinished":false,"lastUpdate":1700}}
                """.trimIndent(),
            ),
        )

        val books = assertIs<AppResult.Success<LibrarySnapshot>>(api().listBooks(PROFILE, LIBRARY)).value.books

        val progress = books.single().book.progress
        assertEquals(PROFILE, progress?.profileId)
        // Rounded to the nearest millisecond rather than truncated, so a position cannot creep backwards.
        assertEquals(4_500L, progress?.position?.inWholeMilliseconds)
    }

    private class FakeConnectionResolver : ProfileConnectionResolver {
        var serverUrl: String = ""
        var access: LibraryAccess = LibraryAccess(hasAllLibraryAccess = true, accessibleLibraryIds = emptyList())

        /** `false` is the state of a profile whose session has expired or whose token cannot be decrypted. */
        var hasConnection: Boolean = true

        override suspend fun connectionFor(profileId: ProfileId): ProfileConnection? {
            if (!hasConnection) return null
            return ProfileConnection(
                profileId = profileId,
                serverId = SERVER,
                serverUrl = serverUrl,
                accessToken = AuthToken("that-profiles-token"),
                access = access,
            )
        }
    }

    private companion object {
        val SERVER = ServerId("srv_books")
        val PROFILE = ProfileId("prf_ada")
        val LIBRARY = LibraryId("lib-1")
    }
}
