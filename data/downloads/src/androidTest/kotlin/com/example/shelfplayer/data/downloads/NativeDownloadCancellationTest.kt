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
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.OfflineFile
import com.example.shelfplayer.core.model.download.StorageVolumeOption
import com.example.shelfplayer.core.model.getOrNull
import com.example.shelfplayer.core.network.gateway.DownloadApi
import com.example.shelfplayer.core.network.gateway.FileTransfer
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DL-001/002, DC-P03: actual Android parser, Room and files in the isolated library test package.
 * A latch holds the synchronous verifier after native parsing; the owning child Job is cancelled
 * while that verifier is still executing. This is not a WorkManager scheduling or HTTP wire test.
 */
class NativeDownloadCancellationTest {
    private lateinit var database: ShelfPlayerDatabase
    private lateinit var repository: DefaultDownloadRepository
    private lateinit var storage: DownloadStorage
    private val logger = RedactingLogger(RecordingLogSink(), DefaultRedactor(RedactionPolicy.Default))
    private val nativeVerifier = AndroidMediaContainerVerifier(logger)

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java).build()
        storage = DownloadStorage(
            context,
            object : DownloadRoots {
                override fun roots(): List<File> = listOf(context.filesDir)
                override fun rootForVolume(uuid: String?): File? =
                    context.filesDir.takeIf { uuid == StorageVolumeOption.INTERNAL_UUID }
            },
        )
        repository = DefaultDownloadRepository(
            database.downloadDao(),
            storage,
            DownloadCopyLocks(),
            TestAppClock(),
            Dispatchers.IO,
        )
        database.profileDao().upsertServer(
            ServerEntity(
                serverId = SERVER.value, displayName = "Native fixture", baseUrl = "https://fixture.invalid",
                detectedVersion = "fixture", isFixture = true, lastFetchedAt = 0,
                authMethodsJson = "[]", capabilitiesJson = "[]", capabilitiesDetectedAt = null,
            ),
        )
        database.profileDao().upsertProfile(
            ProfileEntity(
                profileId = PROFILE.value, serverId = SERVER.value, remoteUserId = null,
                username = "native-fixture", displayName = "Native fixture", role = "Listener",
                requiresReauthentication = false, lastUsedAt = null, isFixture = true,
                accessibleLibrariesJson = "[]", hasAllLibraryAccess = true, hasAllTagAccess = true,
                canDownload = true,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
        storage.deleteItem(SERVER.value, BOOK.value)
    }

    @Test
    fun cancelFreshNativeValidationKeepsPart() = runBlocking {
        cancellationCase(unsatisfiedRange = false, readable = true)
    }

    @Test
    fun cancelValidComplete416NativeValidationDoesNotRename() = runBlocking {
        cancellationCase(unsatisfiedRange = true, readable = true)
    }

    @Test
    fun cancelInvalidComplete416NativeValidationDoesNotDeleteOrRestart() = runBlocking {
        cancellationCase(unsatisfiedRange = true, readable = false)
    }

    @Suppress("LongMethod")
    private suspend fun cancellationCase(unsatisfiedRange: Boolean, readable: Boolean) = coroutineScope {
        val body = if (readable) wave() else ByteArray(32)
        val part = assertNotNull(storage.partFor(SERVER.value, BOOK.value, "native-file", "audio/mpeg"))
        val final = storage.finalFor(part)
        val file = OfflineFile(
            remoteFileId = "native-file", index = 0, uri = "", state = DownloadState.Queued,
            expectedBytes = body.size.toLong(), downloadedBytes = if (unsatisfiedRange) body.size.toLong() else 0,
            mimeType = "audio/mpeg", duration = null, eTag = if (unsatisfiedRange) "\"v1\"" else null,
            lastModified = null,
        )
        repository.request(SERVER, BOOK, PROFILE, listOf(file))
        if (unsatisfiedRange) part.writeBytes(body)
        repository.markPaused(SERVER, BOOK)
        val sibling = storage.finalFor(
            assertNotNull(storage.partFor(SERVER.value, BOOK.value, "sibling", "audio/mpeg")),
        )
        val siblingBody = wave()
        sibling.writeBytes(siblingBody)
        val parsed = CompletableDeferred<Boolean>()
        val release = CountDownLatch(1)
        val verifier = MediaContainerVerifier { candidate ->
            val answer = nativeVerifier.isReadable(candidate)
            parsed.complete(answer)
            check(release.await(10, TimeUnit.SECONDS)) { "Native verifier gate was not released" }
            answer
        }
        val api = TransferFixture(body, unsatisfiedRange)
        val downloader = FileDownloader(api, storage, repository, verifier, NativeRecordingCapabilities(), logger)
        val job = launch(Dispatchers.IO) {
            downloader.download(PROFILE, SERVER, BOOK, file, StorageVolumeOption.INTERNAL_UUID)
        }
        try {
            assertEquals(readable, withTimeout(10_000) { parsed.await() }, "Actual native parser result")
            job.cancel()
        } finally {
            release.countDown()
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(final.exists(), "Cancellation inside verification must never rename the part")
        assertTrue(part.exists(), "Invalid complete416 cancellation must not delete the part")
        assertContentEquals(body, part.readBytes())
        assertContentEquals(siblingBody, sibling.readBytes())
        assertEquals(1, api.requests, "Cancelled invalid416 validation must not restart a transfer")
        val book = assertNotNull(repository.observe(SERVER, BOOK).first())
        assertEquals(DownloadState.Paused, book.state)
        val checkpoint = book.files.single()
        assertEquals(body.size.toLong(), checkpoint.downloadedBytes)
        assertEquals(body.size.toLong(), checkpoint.expectedBytes)
        assertEquals(if (unsatisfiedRange) "\"v1\"" else "\"v2\"", checkpoint.eTag)
        assertEquals(DownloadState.Queued, checkpoint.state)

        // Explicit later Resume uses the normal native validation/commit path and a fresh200 body.
        val resumed = FileDownloader(api, storage, repository, nativeVerifier, NativeRecordingCapabilities(), logger)
            .download(PROFILE, SERVER, BOOK, checkpoint, StorageVolumeOption.INTERNAL_UUID)
        assertEquals(DownloadState.Complete, assertNotNull(resumed.getOrNull()).state)
        assertContentEquals(wave(), final.readBytes())
        assertFalse(part.exists())
        assertContentEquals(siblingBody, sibling.readBytes())
    }

    private class TransferFixture(private val firstBody: ByteArray, private val complete416: Boolean) : DownloadApi {
        var requests = 0
            private set

        override suspend fun fetchFile(
            profileId: ProfileId,
            bookId: LibraryItemId,
            fileId: String,
            sink: (Boolean) -> OutputStream,
            resumeFrom: Long,
            validator: String?,
            onProgress: (Long) -> Unit,
        ): AppResult<FileTransfer> {
            requests++
            if (complete416 && requests == 1) {
                assertEquals(firstBody.size.toLong(), resumeFrom)
                assertEquals("\"v1\"", validator)
                return AppResult.Success(
                    FileTransfer(0, firstBody.size.toLong(), false, "\"v1\"", null, null, rangeNotSatisfiable = true),
                )
            }
            val body = if (requests == 1) firstBody else wave()
            sink(false).use { it.write(body) }
            onProgress(body.size.toLong())
            return AppResult.Success(
                FileTransfer(body.size.toLong(), body.size.toLong(), false, "\"v2\"", null, "audio/mpeg"),
            )
        }

        override suspend fun fetchCover(
            profileId: ProfileId,
            bookId: LibraryItemId,
            sink: () -> OutputStream,
        ): AppResult<String?> = AppResult.Failure(AppError.ApiCompatibility(summary = "No cover in native fixture"))
    }

    private companion object {
        val SERVER = ServerId("native-cancellation-server")
        val BOOK = LibraryItemId("native-cancellation-book")
        val PROFILE = ProfileId("native-cancellation-profile")

        /** One second of synthetic PCM silence; no external copyrighted media or network. */
        fun wave(): ByteArray = ByteBuffer.allocate(16_044).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(16_036)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(8_000)
            putInt(16_000)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(16_000)
        }.array()
    }
}
