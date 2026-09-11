package com.example.shelfplayer.data.downloads

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.OfflineFile
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
import kotlin.test.assertNotNull

/** BW-DL-02 / #107 — safe persisted failure evidence survives the Room-to-domain boundary. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadFailureMappingTest {

    private lateinit var database: ShelfPlayerDatabase
    private lateinit var repository: DefaultDownloadRepository

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DefaultDownloadRepository(
            downloadDao = database.downloadDao(),
            storage = DownloadStorage(context) { listOf(context.filesDir) },
            clock = TestAppClock(),
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        seedAccounts()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `safe failure summary reaches domain without discarding partial bytes`() = runTest {
        repository.request(SERVER, BOOK, PROFILE, listOf(file(downloadedBytes = 0)))
        repository.updateFile(SERVER, BOOK, file(downloadedBytes = 512))

        repository.markFailed(SERVER, BOOK, FAILURE)

        val stored = assertNotNull(repository.observe(SERVER, BOOK).first())
        assertEquals(DownloadState.Failed, stored.state)
        assertEquals(FAILURE, stored.failureSummary)
        assertEquals(512L, stored.downloadedBytes, "recording a failure must preserve resumable partial progress")
        assertEquals(FILE_URI, stored.files.single().uri, "the manifest still points at the same partial file")
    }

    @Test
    fun `unknown future durable state fails conservatively and keeps safe failure evidence`() = runTest {
        repository.request(SERVER, BOOK, PROFILE, listOf(file(downloadedBytes = 256)))
        val key = EntityKey.of(SERVER.value, BOOK.value)
        val row = assertNotNull(database.downloadDao().find(key))
        database.downloadDao().upsertBook(
            row.book.copy(
                state = "FutureDownloadState",
                failureSummary = FAILURE,
            ),
        )

        val stored = assertNotNull(repository.observe(SERVER, BOOK).first())
        assertEquals(DownloadState.Failed, stored.state)
        assertEquals(FAILURE, stored.failureSummary)
        assertEquals(256L, stored.downloadedBytes)
    }

    private fun file(downloadedBytes: Long) = OfflineFile(
        remoteFileId = "file-1",
        index = 0,
        uri = FILE_URI,
        state = DownloadState.Running,
        expectedBytes = 1_024,
        downloadedBytes = downloadedBytes,
        mimeType = "audio/mpeg",
        duration = null,
        eTag = null,
        lastModified = null,
    )

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
        database.profileDao().upsertProfile(
            ProfileEntity(
                profileId = PROFILE.value,
                serverId = SERVER.value,
                remoteUserId = null,
                username = PROFILE.value,
                displayName = PROFILE.value,
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
        val PROFILE = ProfileId("ada")
        const val FAILURE = "The connection was lost. Check the server and try again."
        const val FILE_URI = "file:///offline/server-1/book-1/file-1.mp3"
    }
}
