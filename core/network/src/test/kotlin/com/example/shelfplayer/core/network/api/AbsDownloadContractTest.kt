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
import com.example.shelfplayer.core.network.gateway.FileTransfer
import com.example.shelfplayer.core.network.gateway.ProfileConnection
import com.example.shelfplayer.core.network.gateway.ProfileConnectionResolver
import com.example.shelfplayer.core.network.http.NetworkErrorMapper
import com.example.shelfplayer.core.testing.RecordingLogSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC DL-001 / DL-002 / 17.1 — the download adapter against the shapes `contracts/item-file.json`
 * recorded.
 *
 * Everything asserted here is a decision made from an HTTP status or header, and each one is silent when it
 * is wrong: a missing `If-Range` splices two files together, a `200` treated as a `206` does the same, a
 * `Content-Range` total read as a `Content-Length` reports the wrong size. None of them throws, and none of
 * them is visible without a real request — which is why this test speaks HTTP rather than mocking Retrofit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AbsDownloadContractTest {

    private lateinit var server: MockWebServer
    private val sink = RecordingLogSink()
    private val connections = FakeConnectionResolver()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** The route and the credential, both of which the capture pinned. */
    @Test
    fun `a first request asks for the whole file, with the profile's own token`() = runTest {
        server.enqueue(body("audio"))

        val sink = ByteArrayOutputStream()
        api().fetchFile(PROFILE, BOOK, FILE_ID, { sink })

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/items/$BOOK_ID/file/$FILE_ID", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
        assertNull(request.getHeader("Range"), "nothing to resume from")
        assertNull(request.getHeader("If-Range"), "and so nothing to guard")
        assertEquals("audio", sink.toString())
    }

    /**
     * A resume sends an **open-ended** range and guards it with `If-Range`.
     *
     * Open-ended because the client knows where it stopped and not where the file ends. Guarded because
     * without it a file replaced on the server produces a `206` whose bytes belong to a different
     * recording — the one failure in this feature that produces a plausible file rather than an error.
     */
    @Test
    fun `a resume sends an open-ended range guarded by the stored validator`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 1024-2047/2048")
                .setHeader("Content-Length", "5")
                .setBody("tail!"),
        )

        api().fetchFile(PROFILE, BOOK, FILE_ID, { ByteArrayOutputStream() }, resumeFrom = 1024, validator = "\"v1\"")

        val request = server.takeRequest()
        assertEquals("bytes=1024-", request.getHeader("Range"))
        assertEquals("\"v1\"", request.getHeader("If-Range"))
    }

    /**
     * `If-Range` is never sent on its own.
     *
     * On a request with no `Range` it is not a resume guard at all — it makes an ordinary GET conditional,
     * and a server is entitled to answer `304` with no body. The download would then "succeed" with zero
     * bytes.
     */
    @Test
    fun `a validator without a resume offset is not sent`() = runTest {
        server.enqueue(body("audio"))

        api().fetchFile(PROFILE, BOOK, FILE_ID, { ByteArrayOutputStream() }, resumeFrom = 0, validator = "\"v1\"")

        assertNull(server.takeRequest().getHeader("If-Range"))
    }

    /** A `206` is a resume: the caller is told to append. */
    @Test
    fun `a 206 reports a resumed transfer, and the total comes from Content-Range`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 1024-2047/2048")
                .setHeader("ETag", "\"v1\"")
                .setHeader("Last-Modified", "Thu, 14 Aug 2026 18:00:00 GMT")
                .setBody("tail!"),
        )

        val transfer = succeeds(resumeFrom = 1024, validator = "\"v1\"")

        assertTrue(transfer.wasResumed)
        assertEquals(2048L, transfer.totalBytes, "the number after the slash, not the length of the part")
        assertEquals(5L, transfer.bytesWritten, "which is only what this attempt wrote")
        assertEquals("\"v1\"", transfer.eTag)
        assertEquals("Thu, 14 Aug 2026 18:00:00 GMT", transfer.lastModified)
    }

    /**
     * **A `200` in answer to a range request is a decline, and the caller must be told.**
     *
     * The server either does not do ranges or refused because `If-Range` did not match. Either way the body
     * is the whole file and whatever is on disk is stale. Reported as `wasResumed == false`, and the sink is
     * opened with `append = false` — which is what this asserts, because a caller that appended here would
     * produce a file made of two different recordings.
     */
    @Test
    fun `a 200 in answer to a range request is reported as not resumed`() = runTest {
        server.enqueue(body("the-whole-new-file"))
        var openedForAppend: Boolean? = null

        val transfer = succeeds(resumeFrom = 1024, validator = "\"stale\"", sink = { append ->
            openedForAppend = append
            ByteArrayOutputStream()
        })

        assertFalse(transfer.wasResumed)
        assertEquals(false, openedForAppend, "the destination must be truncated, not appended to")
        assertEquals(18L, transfer.totalBytes, "Content-Length is the whole file on a 200")
    }

    /**
     * `416` — the part on disk is longer than the file now is.
     *
     * A failure rather than an empty success, so the caller retries from zero rather than committing a file
     * it never received.
     */
    @Test
    fun `an unsatisfiable range is a failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(416))

        val result = api().fetchFile(PROFILE, BOOK, FILE_ID, { ByteArrayOutputStream() }, resumeFrom = 9_999)

        assertIs<AppResult.Failure>(result)
    }

    /**
     * PRODUCT_SPEC 5.1 — the capture recorded `401` for an unauthenticated request, and it stays an
     * authentication failure rather than a generic one.
     *
     * The distinction drives AUTH-004: a `401` marks the profile for reauthentication, and a download that
     * reported it as a network error would leave the user retrying forever against a server that will keep
     * refusing.
     */
    @Test
    fun `a 401 is an authentication failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = api().fetchFile(PROFILE, BOOK, FILE_ID, { ByteArrayOutputStream() })

        assertIs<AppError.Authentication>(assertIs<AppResult.Failure>(result).error)
    }

    /** A revoked download permission is an authorization failure, which is not something to retry. */
    @Test
    fun `a 403 is an authorization failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = api().fetchFile(PROFILE, BOOK, FILE_ID, { ByteArrayOutputStream() })

        assertIs<AppError.Authorization>(assertIs<AppResult.Failure>(result).error)
    }

    /** The destination is not touched at all when the request fails, so a good part survives a bad attempt. */
    @Test
    fun `a failed request never opens the destination`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        var opened = false

        api().fetchFile(PROFILE, BOOK, FILE_ID, {
            opened = true
            ByteArrayOutputStream()
        })

        assertFalse(opened, "opening it would truncate a part the next attempt could have resumed")
    }

    /** Progress counts from what was already on disk, so a caller can drive a bar without knowing why. */
    @Test
    fun `progress is reported as a running total including the resumed bytes`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 100-104/105")
                .setBody("tail!"),
        )
        val seen = mutableListOf<Long>()

        api().fetchFile(
            PROFILE,
            BOOK,
            FILE_ID,
            { ByteArrayOutputStream() },
            resumeFrom = 100,
            validator = "\"v1\"",
            onProgress = { seen += it },
        )

        assertEquals(listOf(105L), seen)
    }

    /** PRODUCT_SPEC 14.5 — nothing private reaches the log: no host, no token, no identifiers. */
    @Test
    fun `the log carries no credential and no address`() = runTest {
        server.enqueue(body("audio"))

        api().fetchFile(PROFILE, BOOK, FILE_ID, { ByteArrayOutputStream() })

        assertFalse(sink.text.contains(TOKEN), "the token: ${sink.text}")
        assertFalse(sink.text.contains("localhost"), "the address: ${sink.text}")
        assertFalse(sink.text.contains(BOOK_ID), "the item: ${sink.text}")
        assertTrue(sink.text.contains("Audio file transferred"), "but the event is recorded: ${sink.text}")
    }

    private suspend fun succeeds(
        resumeFrom: Long = 0,
        validator: String? = null,
        sink: (Boolean) -> OutputStream = { ByteArrayOutputStream() },
    ): FileTransfer = assertIs<AppResult.Success<FileTransfer>>(
        api().fetchFile(PROFILE, BOOK, FILE_ID, sink, resumeFrom = resumeFrom, validator = validator),
    ).value

    private fun body(content: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "audio/mpeg")
        .setHeader("ETag", "\"v1\"")
        .setBody(content)

    private fun api(): AbsDownloadApi {
        connections.serverUrl = server.url("/").toString().removeSuffix("/")
        return AbsDownloadApi(
            services = AudiobookshelfServiceFactory(OkHttpClient(), OkHttpClient(), Json { ignoreUnknownKeys = true }),
            connections = connections,
            errors = NetworkErrorMapper(),
            logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default)),
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    private class FakeConnectionResolver : ProfileConnectionResolver {
        var serverUrl: String = ""

        override suspend fun connectionFor(profileId: ProfileId): ProfileConnection = ProfileConnection(
            profileId = profileId,
            serverId = SERVER,
            serverUrl = serverUrl,
            accessToken = AuthToken(TOKEN),
            access = LibraryAccess(hasAllLibraryAccess = true, accessibleLibraryIds = emptyList()),
        )
    }

    private companion object {
        const val TOKEN = "that-profiles-token"
        const val BOOK_ID = "book-salt-harbour"
        const val FILE_ID = "file-1"
        val SERVER = ServerId("server-1")
        val PROFILE = ProfileId("profile-1")
        val BOOK = LibraryItemId(BOOK_ID)
    }
}
