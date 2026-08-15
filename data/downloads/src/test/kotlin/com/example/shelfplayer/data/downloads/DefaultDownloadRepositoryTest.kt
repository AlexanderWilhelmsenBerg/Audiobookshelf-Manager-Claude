package com.example.shelfplayer.data.downloads

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.download.OfflineFile
import com.example.shelfplayer.core.model.getOrNull
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * PRODUCT_SPEC DL-002 / DL-003 — the offline manifest, against a real database.
 *
 * A real Room database rather than a fake DAO, because half of what is being tested is what **SQLite** does:
 * the cascade that decrements a book's references when a profile is deleted (DL-003 criterion 5), and the
 * cascade that takes a book's file rows with it. A fake would only prove that a fake was told to do them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultDownloadRepositoryTest {

    private lateinit var database: ShelfPlayerDatabase
    private lateinit var repository: DefaultDownloadRepository
    private lateinit var storage: DownloadStorage

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
        seedAccounts()
    }

    @After
    fun tearDown() = database.close()

    /** A manifest can be written and read back, which is this slice's whole exit criterion. */
    @Test
    fun `a manifest round-trips`() = runTest {
        val stored = assertNotNull(repository.request(SERVER, BOOK, ADA, files()).getOrNull())

        assertEquals(SERVER, stored.serverId)
        assertEquals(BOOK, stored.itemId)
        assertEquals(DownloadState.Queued, stored.state)
        assertEquals(listOf("file-1", "file-2"), stored.files.map(OfflineFile::remoteFileId))
        assertEquals(setOf(ADA), stored.requestedBy)
        assertEquals(3_000L, stored.totalBytes, "the expected sizes, not the downloaded ones")
        assertEquals(0L, stored.downloadedBytes)
    }

    /**
     * Files come back in the server's order, not the filesystem's.
     *
     * Written out of order on purpose. Alphabetical would be right for the ids here and wrong for a book
     * whose parts are numbered 1 to 12 — track 10 sorts before track 2 — which is the case that fails in
     * somebody's car rather than in a test.
     */
    @Test
    fun `files come back in playback order`() = runTest {
        val shuffled = listOf(file("c", index = 2), file("a", index = 0), file("b", index = 1))

        val stored = assertNotNull(repository.request(SERVER, BOOK, ADA, shuffled).getOrNull())

        assertEquals(listOf("a", "b", "c"), stored.files.map(OfflineFile::remoteFileId))
    }

    /**
     * PRODUCT_SPEC DL-001 — "a book becomes `Downloaded` only after all required audio tracks... are
     * committed", and an atomic commit prevents a crash from faking that.
     *
     * The refusal is the point. A downloader that lost one part must not be able to declare a book playable
     * offline; the failure would otherwise be silence on the third track, in a car, with no network.
     */
    @Test
    fun `a book with an unfinished file cannot be marked complete`() = runTest {
        repository.request(SERVER, BOOK, ADA, files())
        repository.updateFile(SERVER, BOOK, file("file-1", index = 0, state = DownloadState.Complete))

        assertIs<AppResult.Failure>(repository.markComplete(SERVER, BOOK, coverUri = null))
        assertEquals(DownloadState.Queued, assertNotNull(stored()).state, "and the state is left alone")
    }

    /** With every file committed it completes, and an absent cover is not a reason to refuse. */
    @Test
    fun `a book with every file committed completes`() = runTest {
        completeDownload()

        val stored = assertNotNull(stored())
        assertEquals(DownloadState.Complete, stored.state)
        assertTrue(stored.isComplete)
        assertNull(stored.coverUri, "a server with no artwork is ordinary, and the book is still listenable")
    }

    /**
     * PRODUCT_SPEC DL-003 criterion 4 / decision 6 — two profiles, one copy.
     *
     * The owner's words: *"If two different users share a book in the library, I want progress to stay per
     * user and not have to download per user."* So the second request adds a claim and moves no bytes, and
     * the book stays complete rather than being reset to queued by a fresh manifest.
     */
    @Test
    fun `a second profile shares the copy rather than downloading again`() = runTest {
        completeDownload()

        val shared = assertNotNull(repository.request(SERVER, BOOK, GRACE, files()).getOrNull())

        assertEquals(setOf(ADA, GRACE), shared.requestedBy)
        assertEquals(DownloadState.Complete, shared.state, "the existing copy is not reset")
        assertEquals(2, shared.files.size, "and no duplicate rows were written")
    }

    /**
     * PRODUCT_SPEC DL-003 criterion 5 — releasing decrements, and only the last release frees the files.
     *
     * `release` returning `false` is the signal that the bytes may go. It never deletes them itself, because
     * DL-003 requires that removing a server offers a choice — keep the media, export it later, or delete —
     * and a method that always deleted would make the first two impossible.
     */
    @Test
    fun `releasing one claim leaves the other profile's copy alone`() = runTest {
        completeDownload()
        repository.request(SERVER, BOOK, GRACE, files())

        assertEquals(true, repository.release(SERVER, BOOK, ADA).getOrNull(), "Grace still wants it")

        val remaining = assertNotNull(stored())
        assertEquals(setOf(GRACE), remaining.requestedBy)
        assertEquals(DownloadState.Complete, remaining.state, "and the files are untouched")

        assertEquals(false, repository.release(SERVER, BOOK, GRACE).getOrNull(), "now nobody does")
        assertNotNull(stored(), "the manifest survives its last claim, so the files can still be found")
    }

    /** And the unreferenced copy is findable afterwards, which is how a cleanup pass reaches it. */
    @Test
    fun `an unreferenced copy can be found and forgotten`() = runTest {
        completeDownload()
        repository.release(SERVER, BOOK, ADA)

        val orphans = assertNotNull(repository.unreferenced().getOrNull())
        assertEquals(listOf(BOOK), orphans.map(OfflineBook::itemId))

        repository.forget(SERVER, BOOK)
        assertNull(stored())
    }

    /**
     * PRODUCT_SPEC DL-003 criterion 5 — deleting a profile decrements the count without anybody remembering
     * to.
     *
     * Enforced by the foreign key rather than by code, which is why it is tested against a real database:
     * this asserts a `CASCADE` clause, and a fake DAO could not have one.
     */
    @Test
    fun `deleting a profile releases its claims`() = runTest {
        completeDownload()
        repository.request(SERVER, BOOK, GRACE, files())

        database.profileDao().deleteProfile(ADA.value)

        assertEquals(setOf(GRACE), assertNotNull(stored()).requestedBy)
        assertTrue(repository.unreferenced().getOrNull().orEmpty().isEmpty(), "Grace's copy is not orphaned")

        database.profileDao().deleteProfile(GRACE.value)
        assertEquals(
            listOf(BOOK),
            repository.unreferenced().getOrNull().orEmpty().map(OfflineBook::itemId),
            "and now it is, without either deletion touching the files",
        )
    }

    /**
     * PRODUCT_SPEC DL-006 — a pin protects the shared copy, and asking again does not clear it.
     *
     * The second half is the one that would break quietly: a profile that pinned a book and then pressed
     * download again has not asked to unpin it, and an upsert on the request row would have.
     */
    @Test
    fun `a pin survives a second request`() = runTest {
        completeDownload()
        repository.setPinned(SERVER, BOOK, ADA, isPinned = true)

        repository.request(SERVER, BOOK, ADA, files())

        assertTrue(database.downloadDao().isPinned(key()))
    }

    /**
     * PRODUCT_SPEC 5.2 / decision 6 — the storage list is the device's, not the profile's.
     *
     * *"So if I go into a user that doesn't have that book, I can still see all downloaded books in the
     * settings."* The row is visible; whether its **title** may be shown is the screen's decision, and this
     * is the query that makes the choice possible at all.
     */
    @Test
    fun `the storage list shows every download regardless of profile`() = runTest {
        completeDownload()

        repository.observeAll().test {
            assertEquals(listOf(BOOK), awaitItem().map(OfflineBook::itemId))
        }
        repository.observeCompletedFor(GRACE).test {
            assertTrue(awaitItem().isEmpty(), "but Grace has no claim, so nothing of hers is playable offline")
        }
        repository.observeCompletedFor(ADA).test {
            assertEquals(setOf(BOOK), awaitItem())
        }
    }

    /** An incomplete download is not offered as playable, whatever the storage screen shows. */
    @Test
    fun `a queued download is not playable offline`() = runTest {
        repository.request(SERVER, BOOK, ADA, files())

        repository.observeCompletedFor(ADA).test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    /** A failure is recorded with a summary the screen can show, and does not become a complete download. */
    @Test
    fun `a failure is recorded`() = runTest {
        repository.request(SERVER, BOOK, ADA, files())

        repository.markFailed(SERVER, BOOK, summary = "The connection was lost")

        assertEquals(DownloadState.Failed, assertNotNull(stored()).state)
    }

    /** Progress is the sum of what is on disk, which is what a progress bar reads. */
    @Test
    fun `downloaded bytes accumulate across files`() = runTest {
        repository.request(SERVER, BOOK, ADA, files())

        repository.updateFile(SERVER, BOOK, file("file-1", index = 0, downloaded = 1_000))
        repository.updateFile(SERVER, BOOK, file("file-2", index = 1, downloaded = 500))

        assertEquals(1_500L, assertNotNull(stored()).downloadedBytes)
        repository.observeTotalBytes().test { assertEquals(1_500L, awaitItem()) }
    }

    /** Writing a file for a book nobody requested is a bug, and fails rather than creating an orphan row. */
    @Test
    fun `a file cannot be recorded for a book with no manifest`() = runTest {
        assertIs<AppResult.Failure>(repository.updateFile(SERVER, BOOK, file("file-1", index = 0)))
        assertNull(stored())
    }

    /** Forgetting a manifest takes its file rows with it — the cascade, not a second delete somewhere. */
    @Test
    fun `forgetting a manifest removes its files`() = runTest {
        completeDownload()
        assertEquals(3_000L, database.downloadDao().observeTotalBytes().first())

        repository.forget(SERVER, BOOK)

        assertEquals(0L, database.downloadDao().observeTotalBytes().first(), "no file row outlived the book")
    }

    private suspend fun completeDownload() {
        repository.request(SERVER, BOOK, ADA, files())
        listOf("file-1" to 1_000L, "file-2" to 2_000L).forEachIndexed { index, (id, bytes) ->
            repository.updateFile(
                SERVER,
                BOOK,
                file(id, index = index, state = DownloadState.Complete, downloaded = bytes),
            )
        }
        repository.markComplete(SERVER, BOOK, coverUri = null)
    }

    private suspend fun stored(): OfflineBook? = repository.observe(SERVER, BOOK).first()

    private fun files() = listOf(file("file-1", index = 0), file("file-2", index = 1, expected = 2_000))

    private fun file(
        id: String,
        index: Int,
        state: DownloadState = DownloadState.Queued,
        expected: Long? = 1_000,
        downloaded: Long = 0,
    ) = OfflineFile(
        remoteFileId = id,
        index = index,
        uri = "file:///offline/server-1/book-1/$id.mp3",
        state = state,
        expectedBytes = expected,
        downloadedBytes = downloaded,
        mimeType = "audio/mpeg",
        duration = 20.minutes,
        eTag = "\"abc123\"",
        lastModified = "Thu, 14 Aug 2026 18:00:00 GMT",
    )

    private fun key() = EntityKey.of(SERVER.value, BOOK.value)

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
