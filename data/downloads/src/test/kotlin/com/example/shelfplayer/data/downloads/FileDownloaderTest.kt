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

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DefaultDownloadRepository(
            downloadDao = database.downloadDao(),
            clock = TestAppClock(),
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        storage = DownloadStorage(context)
        downloader = FileDownloader(
            downloads = api,
            storage = storage,
            repository = repository,
            verifier = verifier,
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

        var lastResumeFrom: Long = -1
        var lastValidator: String? = null

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
            failure?.let { return AppResult.Failure(it) }

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
