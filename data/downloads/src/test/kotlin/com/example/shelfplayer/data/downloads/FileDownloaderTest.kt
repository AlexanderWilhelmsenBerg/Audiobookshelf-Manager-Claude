package com.example.shelfplayer.data.downloads

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerCapability
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.OfflineFile
import com.example.shelfplayer.core.model.getOrNull
import com.example.shelfplayer.core.network.gateway.DownloadApi
import com.example.shelfplayer.core.network.gateway.FileTransfer
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.OutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC DL-001 / DL-002 — one file, fetched, verified, committed atomically.
 *
 * The gateway is faked and the **filesystem is real**, which is the split that matters: every property
 * worth testing here is about what is on disk after each outcome, and a fake filesystem would only prove
 * that the fake was asked to do the right thing. The container check is faked too — Robolectric has no
 * media stack — and is exercised as the decision it drives rather than as a parser.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileDownloaderTest {

    private lateinit var database: ShelfPlayerDatabase
    private lateinit var repository: DefaultDownloadRepository
    private lateinit var storage: DownloadStorage
    private lateinit var downloader: FileDownloader
    private val api = FakeDownloadApi()
    private val verifier = FakeVerifier()

    private val capabilities = RecordingCapabilities()

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storage = DownloadStorage(context) { listOf(context.filesDir) }
        repository = DefaultDownloadRepository(
            downloadDao = database.downloadDao(),
            storage = storage,
            clock = TestAppClock(),
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        downloader = FileDownloader(
            downloads = api,
            storage = storage,
            repository = repository,
            verifier = verifier,
            capabilities = capabilities,
            logger = RedactingLogger(RecordingLogSink(), DefaultRedactor(RedactionPolicy.Default)),
        )
        seedAccount()
        repository.request(SERVER, BOOK, PROFILE, listOf(queuedFile()))
    }

    @After
    fun tearDown() {
        database.close()
        storage.deleteItem(SERVER.value, BOOK.value)
    }

    /**
     * The whole path: bytes arrive, the part is verified, the part is renamed.
     *
     * The two assertions about *names* are the point. Nothing is ever written under the final name, so a
     * file that exists under one has been through the check — and a `.part` left behind after a success
     * would mean the commit did not happen and the next start-up would try to resume a finished file.
     */
    @Test
    fun `a file is written to a part, verified, then committed under its real name`() = runTest {
        api.body = "audio-bytes".toByteArray()

        val stored = assertNotNull(downloader.download(PROFILE, SERVER, BOOK, queuedFile()).getOrNull())

        assertEquals(DownloadState.Complete, stored.state)
        assertTrue(committed().exists(), "the final file is on disk")
        assertFalse(part().exists(), "and the part is gone")
        assertEquals("audio-bytes", committed().readText())
        assertTrue(stored.uri.endsWith("file-1.mp3"), "the manifest points at the committed name")
    }

    /** DL-003 criterion 1 — the layout is the one the requirement writes, under the app-private root. */
    @Test
    fun `the file lands where DL-003 says`() = runTest {
        api.body = "x".toByteArray()

        downloader.download(PROFILE, SERVER, BOOK, queuedFile())

        val expected = File(
            ApplicationProvider.getApplicationContext<Context>().filesDir,
            "offline/${SERVER.value}/${BOOK.value}/file-1.mp3",
        )
        assertTrue(expected.exists(), "expected $expected, found ${committed()}")
    }

    /** The committed file's real facts reach the manifest — size, type, and the validator for next time. */
    @Test
    fun `the manifest records what the server actually sent`() = runTest {
        api.body = "0123456789".toByteArray()
        api.eTag = "\"v2\""
        api.lastModified = "Thu, 14 Aug 2026 18:00:00 GMT"
        api.contentType = "audio/mp4"

        downloader.download(PROFILE, SERVER, BOOK, queuedFile())

        val file = assertNotNull(repository.observe(SERVER, BOOK).first()).files.single()
        assertEquals(10L, file.downloadedBytes)
        assertEquals(10L, file.expectedBytes)
        assertEquals("\"v2\"", file.eTag)
        assertEquals("Thu, 14 Aug 2026 18:00:00 GMT", file.lastModified)
        assertEquals("audio/mp4", file.mimeType)
    }

    /**
     * PRODUCT_SPEC DL-002 — a body shorter than the server promised does not become a committed file.
     *
     * This is the check that catches a truncated transfer the transport thought succeeded, and the reason
     * the part survives: it is what the next attempt resumes from.
     */
    @Test
    fun `a short file is not committed`() = runTest {
        api.body = "short".toByteArray()
        api.totalBytes = 5_000

        assertIs<AppResult.Failure>(downloader.download(PROFILE, SERVER, BOOK, queuedFile()))

        assertFalse(committed().exists(), "nothing was committed")
        assertTrue(part().exists(), "and the part is kept, because it is what a resume continues from")
        assertEquals(DownloadState.Failed, storedFile().state)
    }

    /**
     * PRODUCT_SPEC DL-002 — "readable media container", the check that catches the right number of the
     * wrong bytes.
     *
     * A captive-portal login page arrives with a `200` and a truthful `Content-Length`. Status, length and
     * non-zero size all pass; only opening it as media does not.
     */
    @Test
    fun `a file that is not media is not committed`() = runTest {
        api.body = "<html>Sign in to continue</html>".toByteArray()
        verifier.isReadable = false

        assertIs<AppResult.Failure>(downloader.download(PROFILE, SERVER, BOOK, queuedFile()))

        assertFalse(committed().exists())
        assertEquals(DownloadState.Failed, storedFile().state)
    }

    /** An empty body is a failure even when the server never said how long the file should be. */
    @Test
    fun `an empty file is not committed`() = runTest {
        api.body = ByteArray(0)
        api.totalBytes = null

        assertIs<AppResult.Failure>(downloader.download(PROFILE, SERVER, BOOK, queuedFile()))

        assertFalse(committed().exists())
    }

    /**
     * A resume sends the bytes on disk and the stored validator, and appends what comes back.
     *
     * The assertion is the *content*: head plus tail, in that order. A resume that re-fetched from zero
     * and appended would produce `headhead-and-tail`, which has the wrong length; one that appended
     * correctly but sent no `If-Range` would be untestable from the result alone, which is why the request
     * is asserted too.
     */
    @Test
    fun `an interrupted file resumes from what is on disk`() = runTest {
        part().parentFile?.mkdirs()
        part().writeText("head")
        api.body = "-and-tail".toByteArray()
        api.wasResumed = true

        val stored = assertNotNull(
            downloader.download(PROFILE, SERVER, BOOK, queuedFile(eTag = "\"v1\"")).getOrNull(),
        )

        assertEquals("head-and-tail", committed().readText())
        assertEquals(4L, api.lastResumeFrom, "the request asked to continue from the four bytes on disk")
        assertEquals("\"v1\"", api.lastValidator, "guarded by If-Range, so a changed file cannot be spliced")
        assertEquals(13L, stored.downloadedBytes)
    }

    /**
     * **The one that silently corrupts a file if it is wrong.**
     *
     * The server declined the range — the file changed since the part was written — and answered with the
     * whole new file. Appending would produce `head` + the new file: plausible-looking, possibly even the
     * right length, and two different recordings joined in the middle. The part must be truncated first.
     */
    @Test
    fun `a declined resume replaces the part instead of appending to it`() = runTest {
        part().parentFile?.mkdirs()
        part().writeText("stale-head")
        api.body = "the-whole-new-file".toByteArray()
        api.wasResumed = false

        downloader.download(PROFILE, SERVER, BOOK, queuedFile(eTag = "\"v1\""))

        assertEquals("the-whole-new-file", committed().readText(), "no trace of the stale head")
    }

    /**
     * A server commonly answers `416` with its complete length when the connection died after every byte
     * was written but before the client received completion. Matching the part to that authoritative length,
     * then running the normal media verification, avoids downloading an already complete file again.
     */
    @Test
    fun `a complete part reported by 416 is verified and committed without another request`() = runTest {
        part().parentFile?.mkdirs()
        part().writeText("complete")
        api.rangeNotSatisfiableOnce = true
        api.rangeNotSatisfiableTotalBytes = 8
        api.rangeNotSatisfiableETag = "\"v1\""

        val stored = assertNotNull(
            downloader.download(PROFILE, SERVER, BOOK, queuedFile(eTag = "\"v1\"")).getOrNull(),
        )

        assertEquals(listOf(8L), api.requests.map { it.first }, "the complete part was not fetched again")
        assertEquals("complete", committed().readText())
        assertEquals(DownloadState.Complete, stored.state)
        assertEquals(8L, stored.downloadedBytes)
    }

    /** Matching length is insufficient when the server contradicts the validator that guarded the range. */
    @Test
    fun `a complete-length part with a changed validator is restarted`() = runTest {
        part().parentFile?.mkdirs()
        part().writeText("complete")
        api.rangeNotSatisfiableOnce = true
        api.rangeNotSatisfiableTotalBytes = 8
        api.rangeNotSatisfiableETag = "\"v2\""
        api.body = "fresh-file".toByteArray()
        api.eTag = "\"v2\""

        val stored = assertNotNull(
            downloader.download(PROFILE, SERVER, BOOK, queuedFile(eTag = "\"v1\"")).getOrNull(),
        )

        assertEquals(listOf(8L, 0L), api.requests.map { it.first })
        assertEquals("fresh-file", committed().readText())
        assertEquals("\"v2\"", stored.eTag)
    }

    /** A stale or oversized part is discarded and followed by exactly one unguarded request from zero. */
    @Test
    fun `a stale part reported by 416 is cleared and restarted once`() = runTest {
        part().parentFile?.mkdirs()
        part().writeText("stale")
        api.rangeNotSatisfiableOnce = true
        api.rangeNotSatisfiableTotalBytes = 3
        api.rangeNotSatisfiableETag = "\"v1\""
        api.body = "fresh-file".toByteArray()
        api.eTag = "\"v2\""

        val stored = assertNotNull(
            downloader.download(PROFILE, SERVER, BOOK, queuedFile(eTag = "\"v1\"")).getOrNull(),
        )

        assertEquals(listOf(5L, 0L), api.requests.map { it.first })
        assertEquals(listOf("\"v1\"", null), api.requests.map { it.second })
        assertEquals("fresh-file", committed().readText(), "the stale bytes were not retained")
        assertEquals("\"v2\"", stored.eTag)
    }

    /**
     * Clear the stale validator before the clean restart. If that restart fails, a later worker must begin
     * cleanly rather than sending the same impossible range forever.
     */
    @Test
    fun `a failed clean restart after 416 does not retain the stale part or validator`() = runTest {
        part().parentFile?.mkdirs()
        part().writeText("stale")
        api.rangeNotSatisfiableOnce = true
        api.rangeNotSatisfiableTotalBytes = 3
        api.rangeNotSatisfiableETag = "\"v1\""
        api.freshFailure = com.example.shelfplayer.core.model.AppError.Network()

        assertIs<AppResult.Failure>(
            downloader.download(PROFILE, SERVER, BOOK, queuedFile(eTag = "\"v1\"")),
        )

        assertEquals(listOf(5L, 0L), api.requests.map { it.first }, "there is only one clean restart")
        assertFalse(part().exists())
        assertNull(storedFile().eTag)
        assertEquals(0L, storedFile().downloadedBytes)
    }

    /**
     * Without a stored validator there is no resume, whatever is on disk.
     *
     * A resume guarded by nothing is a guess that the file has not changed, and the failure it produces is
     * the spliced file above with no way to detect it. Starting over costs bandwidth; the alternative costs
     * a corrupt book.
     */
    @Test
    fun `a part with no validator is not resumed`() = runTest {
        part().parentFile?.mkdirs()
        part().writeText("head")
        api.body = "whole".toByteArray()

        downloader.download(PROFILE, SERVER, BOOK, queuedFile(eTag = null))

        assertEquals(0L, api.lastResumeFrom, "no range was requested")
        assertEquals("whole", committed().readText())
    }

    /**
     * PRODUCT_SPEC SYNC-001 / DL-001 — a resume that worked teaches the app that this server resumes.
     *
     * `/status` cannot be asked whether a server honours `Range`, so the only evidence is a `206` to a real
     * request. Without this the capability would stay unconfirmed for the life of the install and the
     * diagnostics screen would report a server as unable to do something it had just done.
     */
    @Test
    fun `a honoured range records that the server supports resuming`() = runTest {
        part().parentFile?.mkdirs()
        part().writeText("head")
        api.body = "-and-tail".toByteArray()
        api.wasResumed = true

        downloader.download(PROFILE, SERVER, BOOK, queuedFile(eTag = "\"v1\""))

        assertTrue(ServerCapability.RangeDownload to true in capabilities.observations)
    }

    /** And a declined one records the refusal, which is what stops the next attempt asking again. */
    @Test
    fun `a declined range records that the server does not resume`() = runTest {
        part().parentFile?.mkdirs()
        part().writeText("stale")
        api.body = "whole".toByteArray()
        api.wasResumed = false

        downloader.download(PROFILE, SERVER, BOOK, queuedFile(eTag = "\"v1\""))

        assertTrue(ServerCapability.RangeDownload to false in capabilities.observations)
    }

    /**
     * A `200` to a request that never carried a `Range` header proves nothing.
     *
     * This is the case that would otherwise mark every server unable to resume on the first file of the
     * first book — there are no bytes on disk yet, so no range is asked for, and the plain `200` that comes
     * back is not a refusal of anything.
     */
    @Test
    fun `a first download records nothing about ranges`() = runTest {
        api.body = "whole".toByteArray()

        downloader.download(PROFILE, SERVER, BOOK, queuedFile(eTag = "\"v1\""))

        assertTrue(capabilities.observations.none { (capability, _) -> capability == ServerCapability.RangeDownload })
    }

    /**
     * PRODUCT_SPEC DL-002 — a validator in the response confirms staleness detection, and only that.
     *
     * The capability is called `ChecksumOrETag` and what it records is the weaker half of its name: an
     * `ETag` says the file changed, never that these bytes are those bytes (ADR-0018).
     */
    @Test
    fun `a validator in the response records that the server sends one`() = runTest {
        api.body = "x".toByteArray()
        api.eTag = "\"v9\""

        downloader.download(PROFILE, SERVER, BOOK, queuedFile())

        assertTrue(ServerCapability.ChecksumOrETag to true in capabilities.observations)
    }

    /**
     * PRODUCT_SPEC DL-001 — an observed refusal does **not** stop the next attempt asking again.
     *
     * `ServerCapabilities.supports` reads `false` both for "this server refused a range" and for "nothing
     * has asked yet", so a downloader that skipped the range when it read `false` would skip it on the
     * first retry after any interrupted download — disabling resuming everywhere rather than only against
     * the servers that cannot do it. The capability is recorded for diagnostics; what is on disk decides.
     */
    @Test
    fun `a recorded refusal does not stop a later resume being attempted`() = runTest {
        part().parentFile?.mkdirs()
        part().writeText("stale")
        api.body = "whole".toByteArray()
        api.wasResumed = false
        downloader.download(PROFILE, SERVER, BOOK, queuedFile(eTag = "\"v1\""))
        assertTrue(ServerCapability.RangeDownload to false in capabilities.observations)

        part().writeText("head")
        api.body = "-and-tail".toByteArray()
        api.wasResumed = true
        downloader.download(PROFILE, SERVER, BOOK, queuedFile(eTag = "\"v1\""))

        assertEquals(4L, api.lastResumeFrom, "the range was asked for again")
        assertEquals("head-and-tail", committed().readText())
    }

    /** A transport failure records the attempt without destroying what a retry would resume from. */
    @Test
    fun `a failed transfer keeps the part`() = runTest {
        part().parentFile?.mkdirs()
        part().writeText("head")
        api.failure = com.example.shelfplayer.core.model.AppError.Network()

        assertIs<AppResult.Failure>(downloader.download(PROFILE, SERVER, BOOK, queuedFile(eTag = "\"v1\"")))

        assertEquals("head", part().readText(), "untouched")
        assertFalse(committed().exists())
        assertEquals(4L, storedFile().downloadedBytes, "and the manifest knows how far it got")
    }

    /** A redownload over an existing file replaces it rather than silently failing to rename. */
    @Test
    fun `committing over an existing file replaces it`() = runTest {
        committed().parentFile?.mkdirs()
        committed().writeText("the-old-recording")
        api.body = "the-new-recording".toByteArray()

        downloader.download(PROFILE, SERVER, BOOK, queuedFile())

        assertEquals("the-new-recording", committed().readText())
    }

    private suspend fun storedFile(): OfflineFile =
        assertNotNull(repository.observe(SERVER, BOOK).first()).files.single()

    private fun part() = storage.partFor(SERVER.value, BOOK.value, "file-1", "audio/mpeg")

    private fun committed() = storage.finalFor(part())

    private fun queuedFile(eTag: String? = null) = OfflineFile(
        remoteFileId = "file-1",
        index = 0,
        uri = "",
        state = DownloadState.Queued,
        expectedBytes = null,
        downloadedBytes = 0,
        mimeType = "audio/mpeg",
        duration = null,
        eTag = eTag,
        lastModified = null,
    )

    private suspend fun seedAccount() {
        database.profileDao().upsertServer(
            ServerEntity(
                serverId = SERVER.value,
                displayName = "Demo",
                baseUrl = "https://fixture.invalid",
                detectedVersion = "fixture-0",
                isFixture = true,
                lastFetchedAt = 0,
                authMethodsJson = "[]",
                capabilitiesJson = "[]",
                capabilitiesDetectedAt = null,
            ),
        )
        database.profileDao().upsertProfile(
            ProfileEntity(
                profileId = PROFILE.value,
                serverId = SERVER.value,
                remoteUserId = null,
                username = "ada",
                displayName = "Ada",
                role = "Listener",
                requiresReauthentication = false,
                lastUsedAt = null,
                isFixture = true,
                accessibleLibrariesJson = "[]",
                hasAllLibraryAccess = true,
                hasAllTagAccess = true,
                canDownload = true,
            ),
        )
    }

    /**
     * A gateway that writes a fixed body, and records what it was asked for.
     *
     * Hand-written rather than mocked (PRODUCT_SPEC 17.1). It honours the one contract that matters — the
     * sink is opened with the append flag the response justifies — because that is the behaviour the
     * downloader depends on, and a mock would let it be wrong.
     */
    private class FakeDownloadApi : DownloadApi {
        var body: ByteArray = ByteArray(0)
        var totalBytes: Long? = null
        var wasResumed: Boolean = false
        var eTag: String? = "\"v1\""
        var lastModified: String? = null
        var contentType: String? = "audio/mpeg"
        var failure: com.example.shelfplayer.core.model.AppError? = null
        var freshFailure: com.example.shelfplayer.core.model.AppError? = null
        var rangeNotSatisfiableOnce: Boolean = false
        var rangeNotSatisfiableTotalBytes: Long? = null
        var rangeNotSatisfiableETag: String? = null

        var lastResumeFrom: Long = -1
        var lastValidator: String? = null
        val requests = mutableListOf<Pair<Long, String?>>()

        override suspend fun fetchFile(
            profileId: ProfileId,
            bookId: LibraryItemId,
            fileId: String,
            sink: (Boolean) -> OutputStream,
            resumeFrom: Long,
            validator: String?,
            onProgress: (Long) -> Unit,
        ): AppResult<FileTransfer> {
            lastResumeFrom = resumeFrom
            lastValidator = validator
            requests += resumeFrom to validator
            failure?.let { return AppResult.Failure(it) }
            if (rangeNotSatisfiableOnce && resumeFrom > 0) {
                rangeNotSatisfiableOnce = false
                return AppResult.Success(
                    FileTransfer(
                        bytesWritten = 0,
                        totalBytes = rangeNotSatisfiableTotalBytes,
                        wasResumed = false,
                        eTag = rangeNotSatisfiableETag,
                        lastModified = lastModified,
                        contentType = null,
                        rangeNotSatisfiable = true,
                    ),
                )
            }
            if (resumeFrom == 0L) freshFailure?.let { return AppResult.Failure(it) }

            val appended = wasResumed && resumeFrom > 0
            sink(appended).use { stream -> stream.write(body) }
            onProgress((if (appended) resumeFrom else 0) + body.size)
            return AppResult.Success(
                FileTransfer(
                    bytesWritten = body.size.toLong(),
                    totalBytes = totalBytes ?: (body.size.toLong() + if (appended) resumeFrom else 0),
                    wasResumed = appended,
                    eTag = eTag,
                    lastModified = lastModified,
                    contentType = contentType,
                ),
            )
        }

        /** Not exercised here: `BookDownloaderTest` covers the cover, which is a whole-book concern. */
        override suspend fun fetchCover(
            profileId: ProfileId,
            bookId: LibraryItemId,
            sink: () -> OutputStream,
        ): AppResult<String?> = AppResult.Failure(
            com.example.shelfplayer.core.model.AppError.ApiCompatibility(summary = "no cover in this test"),
        )
    }

    /** Robolectric has no media stack, so the container check is a decision rather than a parser here. */
    private class FakeVerifier : MediaContainerVerifier {
        var isReadable: Boolean = true

        override fun isReadable(file: File): Boolean = isReadable
    }

    private companion object {
        val SERVER = ServerId("server-1")
        val BOOK = LibraryItemId("book-1")
        val PROFILE = ProfileId("ada")
    }
}
