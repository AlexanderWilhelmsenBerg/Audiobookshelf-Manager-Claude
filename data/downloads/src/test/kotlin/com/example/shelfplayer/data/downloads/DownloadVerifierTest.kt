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
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.OfflineFile
import com.example.shelfplayer.core.model.getOrNull
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC DL-002 / PLAY-003 — what the start-up check and the *Repair* button actually prove.
 *
 * The filesystem is real, because every claim this class makes is about a file: present, the right length,
 * openable. The container reader is the one fake — Robolectric has no media stack — and the tests say which
 * answer they gave it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadVerifierTest {

    private lateinit var database: ShelfPlayerDatabase
    private lateinit var repository: DefaultDownloadRepository
    private lateinit var directory: File

    /** What the container reader will say. Flipped by the one test that is about the full pass. */
    private var containersReadable = true

    private val logger = RedactingLogger(RecordingLogSink(), DefaultRedactor(RedactionPolicy.Default))

    private val verifier: DownloadVerifier by lazy {
        DownloadVerifier(
            repository = repository,
            verifier = { containersReadable },
            logger = logger,
        )
    }

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DefaultDownloadRepository(
            downloadDao = database.downloadDao(),
            storage = DownloadStorage(context),
            clock = TestAppClock(),
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        directory = File(context.cacheDir, "verifier").apply { mkdirs() }
        seedAccount()
    }

    @After
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun `a complete book whose files are all present verifies`() = runTest {
        completeBook(bytes = 32)

        val report = assertNotNull(verifier.verifyManifests().getOrNull())

        assertEquals(1, report.booksChecked)
        assertEquals(1, report.filesChecked)
        assertTrue(report.isIntact)
    }

    /**
     * The failure this exists for: a user clearing the app's storage, an SD card pulled, a filesystem that
     * lost a file. PLAY-003 — a missing local part must prevent a false downloaded state.
     */
    @Test
    fun `a missing file breaks its book and turns the download back into a retry`() = runTest {
        val stored = completeBook(bytes = 32)
        File(java.net.URI(stored.files.single().uri)).delete()

        val report = assertNotNull(verifier.verifyManifests().getOrNull())

        assertEquals(1, report.booksBroken)
        assertFalse(report.isIntact)
        val after = assertNotNull(repository.observe(SERVER, BOOK).first())
        assertFalse(after.isComplete, "so the button offers a retry")
        assertEquals(DownloadState.Failed, after.files.single().state)
    }

    /** A file truncated by a full disk opens perfectly well and is still not the book. */
    @Test
    fun `a file of the wrong length breaks its book`() = runTest {
        val stored = completeBook(bytes = 32)
        File(java.net.URI(stored.files.single().uri)).writeBytes(ByteArray(8))

        val report = assertNotNull(verifier.verifyManifests().getOrNull())

        assertEquals(1, report.booksBroken)
    }

    /** DL-002: *"corrupt files are quarantined or removed only after user-visible confirmation."* */
    @Test
    fun `a broken file is marked rather than deleted`() = runTest {
        val stored = completeBook(bytes = 32)
        val onDisk = File(java.net.URI(stored.files.single().uri))
        onDisk.writeBytes(ByteArray(8))

        verifier.verifyManifests()

        assertTrue(onDisk.exists(), "the bytes stay until somebody says otherwise")
    }

    /**
     * The cheap pass reads metadata only. A file that is the right length but is not a media container
     * passes [DownloadVerifier.verifyManifests] and fails [DownloadVerifier.verifyFully], which is the
     * whole difference between the two.
     */
    @Test
    fun `only the full pass opens the file as media`() = runTest {
        completeBook(bytes = 32)
        containersReadable = false

        assertTrue(assertNotNull(verifier.verifyManifests().getOrNull()).isIntact)
        assertEquals(1, assertNotNull(verifier.verifyFully().getOrNull()).booksBroken)
    }

    /**
     * An interrupted download has nothing to verify: its files are *expected* to be missing, and counting
     * it would turn every cancelled download into a warning on the next launch.
     */
    @Test
    fun `an incomplete download is not checked`() = runTest {
        repository.request(SERVER, BOOK, ADA, listOf(file(bytes = 32, state = DownloadState.Running)))

        val report = assertNotNull(verifier.verifyManifests().getOrNull())

        assertEquals(0, report.booksChecked)
        assertTrue(report.isIntact)
    }

    /**
     * Decision 4's user-chosen folder. A `content://` location cannot be checked with `File`, and a
     * verifier that failed a book because it could not read its storage would destroy what it exists to
     * protect.
     */
    @Test
    fun `a book in a user-chosen folder is reported intact rather than broken`() = runTest {
        repository.request(
            SERVER,
            BOOK,
            ADA,
            listOf(file(bytes = 32, uri = "content://com.android.externalstorage/tree/primary/1.mp3")),
        )
        repository.updateFile(
            SERVER,
            BOOK,
            file(bytes = 32, uri = "content://com.android.externalstorage/tree/primary/1.mp3")
                .copy(state = DownloadState.Complete, downloadedBytes = 32),
        )

        val report = assertNotNull(verifier.verifyManifests().getOrNull())

        assertEquals(1, report.booksChecked)
        assertTrue(report.isIntact)
    }

    /** Writes a real file and records a manifest that claims it, which is what a finished download leaves. */
    private suspend fun completeBook(bytes: Int) = run {
        val onDisk = File(directory, "1.mp3").apply { writeBytes(ByteArray(bytes)) }
        val planned = file(bytes = bytes.toLong(), uri = onDisk.toURI().toString())
        repository.request(SERVER, BOOK, ADA, listOf(planned))
        repository.updateFile(
            SERVER,
            BOOK,
            planned.copy(state = DownloadState.Complete, downloadedBytes = bytes.toLong()),
        )
        assertNotNull(repository.observe(SERVER, BOOK).first())
    }

    private fun file(
        bytes: Long,
        uri: String = File(directory, "1.mp3").toURI().toString(),
        state: DownloadState = DownloadState.Queued,
    ) = OfflineFile(
        remoteFileId = "file-1",
        index = 0,
        uri = uri,
        state = state,
        expectedBytes = bytes,
        downloadedBytes = if (state == DownloadState.Queued) 0 else bytes,
        mimeType = "audio/mpeg",
        duration = null,
        eTag = null,
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
                profileId = ADA.value,
                serverId = SERVER.value,
                remoteUserId = null,
                username = ADA.value,
                displayName = ADA.value,
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

    private companion object {
        val SERVER = ServerId("server-1")
        val BOOK = LibraryItemId("book-1")
        val ADA = ProfileId("ada")
    }
}
