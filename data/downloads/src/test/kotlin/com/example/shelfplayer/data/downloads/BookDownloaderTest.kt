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
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadPaths
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
 * PRODUCT_SPEC DL-001 / DL-003 — a whole book, and what happens to it afterwards.
 *
 * The filesystem is real and the gateway is faked, the same split `FileDownloaderTest` uses: everything
 * worth asserting is about what is on disk and in the manifest once a run ends, and a fake filesystem would
 * only prove that a fake was asked nicely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookDownloaderTest {

    private lateinit var database: ShelfPlayerDatabase
    private lateinit var repository: DefaultDownloadRepository
    private lateinit var storage: DownloadStorage
    private lateinit var downloader: BookDownloader
    private val api = ScriptedDownloadApi()

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storage = DownloadStorage(context)
        val logger = RedactingLogger(RecordingLogSink(), DefaultRedactor(RedactionPolicy.Default))
        repository = DefaultDownloadRepository(
            downloadDao = database.downloadDao(),
            storage = storage,
            clock = TestAppClock(),
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        downloader = BookDownloader(
            repository = repository,
            fileDownloader = FileDownloader(
                downloads = api,
                storage = storage,
                repository = repository,
                // Robolectric has no media stack, so the container check is a decision here
                // rather than a parser. `FileDownloaderTest` covers what it decides.
                verifier = MediaContainerVerifier { true },
                logger = logger,
            ),
            storage = storage,
            logger = logger,
        )
        seedAccounts()
    }

    @After
    fun tearDown() {
        database.close()
        File(ApplicationProvider.getApplicationContext<Context>().filesDir, DownloadPaths.ROOT_DIRECTORY)
            .deleteRecursively()
    }

    /** Every file, then the commit that makes the book playable offline. */
    @Test
    fun `a book downloads every one of its files and becomes complete`() = runTest {
        repository.request(SERVER, BOOK, ADA, files())

        val done = assertNotNull(downloader.download(ADA, SERVER, BOOK).getOrNull())

        assertEquals(DownloadState.Complete, done.state)
        assertTrue(done.isComplete)
        assertEquals(3, api.fetched.size, "one request per file")
        assertEquals(listOf("file-1", "file-2", "file-3"), api.fetched, "in playback order")
        done.files.forEach { file -> assertTrue(File(java.net.URI(file.uri)).exists(), file.uri) }
    }

    /**
     * PRODUCT_SPEC DL-001 — the progress a notification and a ring read, weighted by bytes.
     *
     * The middle file is ten times the others, so a fraction that counted *files* would report a third
     * after the first one and this asserts otherwise. A book whose parts differ in length is the normal
     * case, not an edge one.
     */
    @Test
    fun `progress is weighted by size rather than by file count`() = runTest {
        repository.request(SERVER, BOOK, ADA, files(sizes = listOf(100, 1_000, 100)))
        val seen = mutableListOf<Float>()

        downloader.download(ADA, SERVER, BOOK) { seen += it }

        assertTrue(seen.isNotEmpty())
        val afterFirst = seen.first { it > 0f }
        assertTrue(afterFirst < 0.2f, "the first of three files is a twelfth of the bytes, not a third: $afterFirst")
        assertEquals(1f, seen.last())
    }

    /**
     * The first failure ends the run, and nothing already fetched is thrown away.
     *
     * Continuing would spend a metered connection on files that cannot make the book playable anyway — it is
     * not `Downloaded` until all of them are committed — and would turn one bad moment into three failures.
     */
    @Test
    fun `a failed file stops the book and keeps what arrived`() = runTest {
        repository.request(SERVER, BOOK, ADA, files())
        api.failOn = "file-2"

        assertIs<AppResult.Failure>(downloader.download(ADA, SERVER, BOOK))

        val stored = assertNotNull(repository.observe(SERVER, BOOK).first())
        assertEquals(DownloadState.Failed, stored.state)
        assertEquals(
            listOf(DownloadState.Complete, DownloadState.Failed, DownloadState.Queued),
            stored.files.map { it.state },
            "the third was never attempted",
        )
        assertEquals(listOf("file-1", "file-2"), api.fetched)
    }

    /** And a retry picks up where it stopped rather than re-fetching what is already committed. */
    @Test
    fun `a retry only fetches what is missing`() = runTest {
        repository.request(SERVER, BOOK, ADA, files())
        api.failOn = "file-2"
        downloader.download(ADA, SERVER, BOOK)
        api.failOn = null
        api.fetched.clear()

        val done = assertNotNull(downloader.download(ADA, SERVER, BOOK).getOrNull())

        assertTrue(done.isComplete)
        assertEquals(listOf("file-2", "file-3"), api.fetched, "the committed first file was not fetched again")
    }

    /**
     * PRODUCT_SPEC DL-003 criterion 5 — removing releases a claim, and only the last one deletes.
     *
     * Both halves in one test because the assertion is the *transition*: after Ada releases, Grace's copy is
     * still on disk; after Grace does, it is gone. Either half alone would pass against a `remove` that
     * always deleted or never did.
     */
    @Test
    fun `removing deletes only when the last profile lets go`() = runTest {
        repository.request(SERVER, BOOK, ADA, files())
        downloader.download(ADA, SERVER, BOOK)
        repository.request(SERVER, BOOK, GRACE, files())
        val onDisk = itemDirectory()

        assertEquals(false, downloader.remove(ADA, SERVER, BOOK).getOrNull(), "Grace still wants it")
        assertTrue(onDisk.exists(), "so nothing on disk changed")

        assertEquals(true, downloader.remove(GRACE, SERVER, BOOK).getOrNull())
        assertFalse(onDisk.exists(), "the files are gone")
        assertNull(repository.observe(SERVER, BOOK).first(), "and so is the manifest")
    }

    /**
     * PRODUCT_SPEC DL-001 — a `.part` outlives a failure on purpose, and outlives its manifest by mistake.
     *
     * The sweep is for the second. A book whose manifest went — an item deleted upstream, a crash between
     * the bytes and the row — leaves files nothing will ever ask for and nothing else in the app would ever
     * find, because every other path starts from a manifest.
     */
    @Test
    fun `the sweep removes files no manifest claims, and leaves the ones that are claimed`() = runTest {
        repository.request(SERVER, BOOK, ADA, files())
        downloader.download(ADA, SERVER, BOOK)

        val orphan = File(itemDirectory().parentFile, "vanished-book").apply {
            mkdirs()
            File(this, "file-9.mp3.part").writeText("bytes nobody will ever ask for")
        }

        val reclaimed = assertNotNull(downloader.sweepOrphans().getOrNull())

        assertFalse(orphan.exists(), "the orphan went")
        assertTrue(reclaimed > 0, "and its bytes were counted")
        assertTrue(itemDirectory().exists(), "while the claimed book was left alone")
    }

    /** Discarding a partial download keeps the manifest, so the book reads as not-downloaded rather than gone. */
    @Test
    fun `discarding partials keeps the manifest`() = runTest {
        repository.request(SERVER, BOOK, ADA, files())
        // Bytes written and then the connection dropped, which is the only way a part survives a failure.
        api.truncateOn = "file-1"
        downloader.download(ADA, SERVER, BOOK)
        assertTrue(partsIn(itemDirectory()).isNotEmpty(), "a part exists to discard")

        downloader.discardPartials(SERVER, BOOK)

        assertTrue(partsIn(itemDirectory()).isEmpty())
        assertNotNull(repository.observe(SERVER, BOOK).first(), "the book is still known, just not downloaded")
    }

    private fun itemDirectory() = File(
        ApplicationProvider.getApplicationContext<Context>().filesDir,
        DownloadPaths.itemDirectory(SERVER.value, BOOK.value).joinToString(File.separator),
    )

    private fun partsIn(directory: File) = directory.listFiles().orEmpty().filter { DownloadPaths.isPart(it.name) }

    private fun files(sizes: List<Long> = listOf(100, 100, 100)) = sizes.mapIndexed { index, size ->
        OfflineFile(
            remoteFileId = "file-${index + 1}",
            index = index,
            uri = "",
            state = DownloadState.Queued,
            expectedBytes = size,
            downloadedBytes = 0,
            mimeType = "audio/mpeg",
            duration = null,
            eTag = null,
            lastModified = null,
        )
    }

    /** Writes a body the size the caller asked for, records what it was asked for, and can be told to fail. */
    private class ScriptedDownloadApi : DownloadApi {
        val fetched = mutableListOf<String>()
        var failOn: String? = null

        /** Writes bytes and *then* fails, which is how a real dropped connection leaves a `.part` behind. */
        var truncateOn: String? = null

        override suspend fun fetchFile(
            profileId: ProfileId,
            bookId: LibraryItemId,
            fileId: String,
            sink: (Boolean) -> OutputStream,
            resumeFrom: Long,
            validator: String?,
            onProgress: (Long) -> Unit,
        ): AppResult<FileTransfer> {
            fetched += fileId
            if (fileId == failOn) return AppResult.Failure(AppError.Network())
            if (fileId == truncateOn) {
                sink(false).use { stream -> stream.write(ByteArray(BODY_BYTES / 2)) }
                return AppResult.Failure(AppError.Network())
            }
            val body = ByteArray(BODY_BYTES) { 'a'.code.toByte() }
            sink(false).use { stream -> stream.write(body) }
            onProgress(body.size.toLong())
            return AppResult.Success(
                FileTransfer(
                    bytesWritten = body.size.toLong(),
                    totalBytes = body.size.toLong(),
                    wasResumed = false,
                    eTag = "\"$fileId\"",
                    lastModified = null,
                    contentType = "audio/mpeg",
                ),
            )
        }

        private companion object {
            /** Small and constant. What is asserted is the *weighting*, which comes from the manifest. */
            const val BODY_BYTES = 8
        }
    }

    private suspend fun seedAccounts() {
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
        listOf(ADA, GRACE).forEach { profile ->
            database.profileDao().upsertProfile(
                ProfileEntity(
                    profileId = profile.value,
                    serverId = SERVER.value,
                    remoteUserId = null,
                    username = profile.value,
                    displayName = profile.value,
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
    }

    private companion object {
        val SERVER = ServerId("server-1")
        val BOOK = LibraryItemId("book-1")
        val ADA = ProfileId("ada")
        val GRACE = ProfileId("grace")
    }
}
