package com.example.shelfplayer.feature.downloads

import app.cash.turbine.test
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.auth.AccountProgress
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.download.OfflineFile
import com.example.shelfplayer.core.model.download.StorageVolumeOption
import com.example.shelfplayer.core.model.download.VerificationReport
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.testing.MainDispatcherRule
import com.example.shelfplayer.domain.download.BookAssetSource
import com.example.shelfplayer.domain.download.BookAssets
import com.example.shelfplayer.domain.download.DownloadLocations
import com.example.shelfplayer.domain.download.DownloadScheduler
import com.example.shelfplayer.domain.download.OfflineFiles
import com.example.shelfplayer.domain.download.OfflineVerification
import com.example.shelfplayer.domain.repository.DownloadRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.DownloadBookUseCase
import com.example.shelfplayer.domain.usecase.PauseDownloadUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC DL-003 / SET-002 / ADR-0018 decisions 6 and 8 — *Manage local files*.
 *
 * The two claims worth proving are in tension with each other, so they are both here: every download on
 * the device is listed (decision 6), and a book the active profile may not see is listed **without its
 * title** (PRODUCT_SPEC 5.2). A screen that got either half alone would look correct in a demo.
 */
class DownloadsViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val downloads = FakeDownloads()
    private val files = FakeFiles()
    private val verification = FakeVerification()
    private val library = FakeLibraries()

    @Test
    fun `lists a download this profile can see, with its title`() = runTest {
        downloads.emit(listOf(offlineBook("tidewatch")))
        library.emit(listOf(book("tidewatch", "Tidewatch")))

        viewModel().uiState.test {
            val row = awaitItem().books.singleOrNull() ?: awaitItem().books.single()
            assertEquals("Tidewatch", row.title)
            assertEquals("Marisol Holt", row.author)
        }
    }

    /**
     * Decision 6 and 5.2 at once. The row exists — it is using space on this device, which is a fact about
     * the device — and it has no name, because naming it would show one profile another's library.
     */
    @Test
    fun `lists a download from a library this profile cannot see, without its title`() = runTest {
        downloads.emit(listOf(offlineBook("someone-elses")))
        library.emit(emptyList())

        viewModel().uiState.test {
            val state = awaitItem().takeIf { it.isLoaded } ?: awaitItem()
            val row = state.books.single()
            assertNull(row.title, "PRODUCT_SPEC 5.2 — not this profile's book to name")
            assertEquals(LibraryItemId("someone-elses"), row.bookId, "but still removable")
        }
    }

    @Test
    fun `reports the total the downloads occupy`() = runTest {
        downloads.emit(listOf(offlineBook("tidewatch"), offlineBook("harrow")))

        viewModel().uiState.test {
            val state = awaitItem().takeIf { it.isLoaded } ?: awaitItem()
            assertEquals(2_048, state.totalBytes)
        }
    }

    @Test
    fun `removing a copy nobody else wants says nothing`() = runTest {
        downloads.emit(listOf(offlineBook("tidewatch")))
        val viewModel = viewModel()

        viewModel.onRemove(LibraryItemId("tidewatch"), SERVER)

        assertEquals(listOf(LibraryItemId("tidewatch")), files.removed)
        assertNull(viewModel.message.value)
    }

    /**
     * DL-003 criterion 5. A silent success would leave the user watching the total not move and wondering
     * which of the two of them is broken.
     */
    @Test
    fun `removing a shared copy explains why nothing was freed`() = runTest {
        downloads.emit(listOf(offlineBook("tidewatch", requestedBy = setOf(ADA, GRACE))))
        files.refusals += LibraryItemId("tidewatch")
        val viewModel = viewModel()

        viewModel.onRemove(LibraryItemId("tidewatch"), SERVER)

        val message = assertNotNull(viewModel.message.value)
        assertTrue(message.contains("another profile"), message)
    }

    @Test
    fun `a failed removal reports the reason rather than nothing`() = runTest {
        downloads.emit(listOf(offlineBook("tidewatch")))
        files.failure = AppError.Storage(summary = "The SD card is not mounted.")
        val viewModel = viewModel()

        viewModel.onRemove(LibraryItemId("tidewatch"), SERVER)

        assertEquals("The SD card is not mounted.", viewModel.message.value)
    }

    @Test
    fun `a shared copy is marked so the dialog can say so before the tap`() = runTest {
        downloads.emit(listOf(offlineBook("tidewatch", requestedBy = setOf(ADA, GRACE))))

        viewModel().uiState.test {
            val state = awaitItem().takeIf { it.isLoaded } ?: awaitItem()
            assertTrue(state.books.single().isSharedWithAnotherProfile)
        }
    }

    @Test
    fun `verification reports what it found`() = runTest {
        verification.report = VerificationReport(booksChecked = 2, filesChecked = 9, booksBroken = 0)
        val viewModel = viewModel()

        viewModel.onVerify()

        val message = assertNotNull(viewModel.message.value)
        assertTrue(message.contains("9"), message)
        assertTrue(message.contains("2"), message)
    }

    /** DL-002 — a broken book offers a retry, and the message says nothing was deleted. */
    @Test
    fun `verification says nothing was deleted when it found a broken book`() = runTest {
        verification.report = VerificationReport(booksChecked = 2, filesChecked = 9, booksBroken = 1)
        val viewModel = viewModel()

        viewModel.onVerify()

        val message = assertNotNull(viewModel.message.value)
        assertTrue(message.contains("Nothing was deleted"), message)
    }

    @Test
    fun `pinning writes the pin for the active profile`() = runTest {
        downloads.emit(listOf(offlineBook("tidewatch")))
        val viewModel = viewModel()

        viewModel.onPinnedChanged(LibraryItemId("tidewatch"), SERVER, isPinned = true)

        assertEquals(listOf(LibraryItemId("tidewatch") to true), downloads.pinned)
    }

    @Test
    fun `dismissing a message clears it`() = runTest {
        verification.report = VerificationReport(booksChecked = 1, filesChecked = 1)
        val viewModel = viewModel()
        viewModel.onVerify()
        assertNotNull(viewModel.message.value)

        viewModel.onMessageShown()

        assertNull(viewModel.message.value)
    }

    private fun viewModel() = DownloadsViewModel(
        downloads = downloads,
        files = files,
        verification = verification,
        profiles = FakeProfiles(),
        locations = locations,
        // The pause pair is constructed against the same fakes. `PauseDownloadUseCaseTest` in `:domain` is
        // where the transitions are asserted; here they exist so the screen has something to call.
        pauseDownload = PauseDownloadUseCase(FakeProfiles(), downloads, InertScheduler),
        downloadBook = DownloadBookUseCase(FakeProfiles(), InertAssets, downloads, InertScheduler),
        library = library,
    )

    /** Neither half of the pause pair is under test here, so neither is allowed to reach anything real. */
    private object InertScheduler : DownloadScheduler {
        override suspend fun enqueue(serverId: ServerId, itemId: LibraryItemId) = Unit

        override suspend fun cancel(serverId: ServerId, itemId: LibraryItemId) = Unit
    }

    private object InertAssets : BookAssetSource {
        override suspend fun assetsFor(profileId: ProfileId, bookId: LibraryItemId): AppResult<BookAssets> =
            AppResult.Success(BookAssets(files = emptyList(), coverUrl = null, estimatedBytes = 0))
    }

    private val locations = FakeLocations()

    /**
     * PRODUCT_SPEC DL-003 / ADR-0020 — the volumes offered, and the one in use.
     *
     * A phone with no card gets one option, and the screen does not draw a picker for it. The list is what
     * decides that, so it is worth asserting rather than assuming.
     */
    @Test
    fun `the volumes this device can write to are offered`() = runTest {
        val viewModel = viewModel()

        assertEquals(listOf("", "card-1"), viewModel.volumes.value.map { it.uuid })
        assertEquals(StorageVolumeOption.INTERNAL_UUID, viewModel.selectedVolume.value)
    }

    @Test
    fun `choosing a volume stores it`() = runTest {
        val viewModel = viewModel()

        viewModel.onVolumeChosen("card-1")

        assertEquals("card-1", locations.selected.value)
    }

    /** A refused write says why. Silently leaving the radio where it was would look like a dead control. */
    @Test
    fun `a refused volume change reports the reason`() = runTest {
        locations.failure = AppError.Storage(summary = "That card is no longer mounted.")
        val viewModel = viewModel()

        viewModel.onVolumeChosen("card-1")

        assertEquals("That card is no longer mounted.", viewModel.message.value)
    }

    private fun book(id: String, title: String) = Book(
        serverId = SERVER,
        id = LibraryItemId(id),
        libraryId = LibraryId("library-1"),
        title = title,
        subtitle = null,
        authors = listOf(Author(SERVER, AuthorId("author-1"), "Marisol Holt")),
        narrators = emptyList(),
        seriesMemberships = emptyList(),
        duration = kotlin.time.Duration.ZERO,
        description = null,
        genres = emptyList(),
        tags = emptyList(),
        publishedYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        isExplicit = false,
        isAbridged = false,
        coverPath = null,
        trackCount = 1,
        sizeBytes = 0,
        remoteUpdatedAt = null,
        addedAt = null,
        lastFetchedAt = Instant.EPOCH,
        progress = null,
        localAvailability = LocalAvailability.NotDownloaded,
    )

    private fun offlineBook(id: String, requestedBy: Set<ProfileId> = setOf(ADA)) = OfflineBook(
        serverId = SERVER,
        itemId = LibraryItemId(id),
        state = DownloadState.Complete,
        files = listOf(
            OfflineFile(
                remoteFileId = "$id-1",
                index = 0,
                uri = "file:///downloads/$id/1.mp3",
                state = DownloadState.Complete,
                expectedBytes = 1_024,
                downloadedBytes = 1_024,
                mimeType = "audio/mpeg",
                duration = null,
                eTag = null,
                lastModified = null,
            ),
        ),
        coverUri = null,
        requestedBy = requestedBy,
        isPinned = false,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private class FakeDownloads : DownloadRepository {
        private val stored = MutableStateFlow<List<OfflineBook>>(emptyList())
        val pinned = mutableListOf<Pair<LibraryItemId, Boolean>>()

        fun emit(books: List<OfflineBook>) {
            stored.value = books
        }

        override fun observeAll(): Flow<List<OfflineBook>> = stored

        override fun observe(serverId: ServerId, itemId: LibraryItemId): Flow<OfflineBook?> =
            stored.map { all -> all.firstOrNull { it.itemId == itemId } }

        override fun observeCompletedFor(profileId: ProfileId): Flow<Set<LibraryItemId>> = flowOf(emptySet())

        override fun observeTotalBytes(): Flow<Long> = stored.map { all -> all.sumOf(OfflineBook::downloadedBytes) }

        override suspend fun freeBytes(): Long = Long.MAX_VALUE

        override suspend fun request(
            serverId: ServerId,
            itemId: LibraryItemId,
            profileId: ProfileId,
            files: List<OfflineFile>,
        ): AppResult<OfflineBook> = notUsed()

        override suspend fun updateFile(
            serverId: ServerId,
            itemId: LibraryItemId,
            file: OfflineFile,
        ): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun markComplete(
            serverId: ServerId,
            itemId: LibraryItemId,
            coverUri: String?,
        ): AppResult<OfflineBook> = notUsed()

        override suspend fun markFailed(serverId: ServerId, itemId: LibraryItemId, summary: String): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun markPaused(serverId: ServerId, itemId: LibraryItemId): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun markQueued(serverId: ServerId, itemId: LibraryItemId): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun setPinned(
            serverId: ServerId,
            itemId: LibraryItemId,
            profileId: ProfileId,
            isPinned: Boolean,
        ): AppResult<Unit> {
            pinned += itemId to isPinned
            return AppResult.Success(Unit)
        }

        override suspend fun release(
            serverId: ServerId,
            itemId: LibraryItemId,
            profileId: ProfileId,
        ): AppResult<Boolean> = AppResult.Success(false)

        override suspend fun unreferenced(): AppResult<List<OfflineBook>> = AppResult.Success(emptyList())

        override suspend fun forget(serverId: ServerId, itemId: LibraryItemId): AppResult<Unit> =
            AppResult.Success(Unit)

        private fun <T> notUsed(): AppResult<T> =
            AppResult.Failure(AppError.ApiCompatibility(summary = "not part of this fake"))
    }

    private class FakeFiles : OfflineFiles {
        val removed = mutableListOf<LibraryItemId>()
        val refusals = mutableSetOf<LibraryItemId>()
        var failure: AppError? = null

        override suspend fun remove(
            profileId: ProfileId,
            serverId: ServerId,
            bookId: LibraryItemId,
        ): AppResult<Boolean> {
            failure?.let { return AppResult.Failure(it) }
            removed += bookId
            return AppResult.Success(bookId !in refusals)
        }

        override suspend fun discardPartials(serverId: ServerId, bookId: LibraryItemId): AppResult<Long> =
            AppResult.Success(0)

        override suspend fun sweepOrphans(): AppResult<Long> = AppResult.Success(0)
    }

    private class FakeVerification : OfflineVerification {
        var report = VerificationReport()

        override suspend fun verifyManifests(): AppResult<VerificationReport> = AppResult.Success(report)

        override suspend fun verifyFully(): AppResult<VerificationReport> = AppResult.Success(report)
    }

    /** PRODUCT_SPEC DL-003 / ADR-0020 — a device with internal storage and one card in it. */
    private class FakeLocations : DownloadLocations {
        val selected = MutableStateFlow(StorageVolumeOption.INTERNAL_UUID)
        var failure: AppError? = null

        override suspend fun options(): List<StorageVolumeOption> = listOf(
            StorageVolumeOption(uuid = "", label = "Internal", freeBytes = 8_000, isRemovable = false),
            StorageVolumeOption(uuid = "card-1", label = "SD card", freeBytes = 64_000, isRemovable = true),
        )

        override fun observeSelected(): Flow<String> = selected

        override suspend fun select(uuid: String): AppResult<Unit> {
            failure?.let { return AppResult.Failure(it) }
            selected.value = uuid
            return AppResult.Success(Unit)
        }
    }

    private class FakeProfiles : ProfileRepository {
        private val active = MutableStateFlow<Profile?>(
            Profile(
                id = ADA,
                serverId = SERVER,
                username = "ada",
                displayName = "ada",
                role = ProfileRole.Listener,
                requiresReauthentication = false,
                lastUsedAt = Instant.EPOCH,
                isFixture = false,
            ),
        )

        override fun observeProfiles(): Flow<List<Profile>> = flowOf(emptyList())

        override fun observeServers(): Flow<List<Server>> = flowOf(emptyList())

        override fun observeActiveProfile(): Flow<Profile?> = active

        override suspend fun activeProfileId(): ProfileId? = active.value?.id

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class FakeLibraries : LibraryRepository {
        private val stored = MutableStateFlow<List<Book>>(emptyList())

        fun emit(books: List<Book>) {
            stored.value = books
        }

        override fun observeLibraries(profileId: ProfileId): Flow<List<Library>> = flowOf(emptyList())

        override fun observeLibrary(profileId: ProfileId, libraryId: LibraryId): Flow<Library?> = flowOf(null)

        override fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>> = stored

        override fun observeAccessibleBooks(profileId: ProfileId): Flow<List<Book>> = stored

        override fun observeChapters(profileId: ProfileId, bookId: LibraryItemId) =
            flowOf(emptyList<com.example.shelfplayer.core.model.library.Chapter>())

        override fun observeBook(profileId: ProfileId, bookId: LibraryItemId): Flow<Book?> =
            stored.map { all -> all.firstOrNull { it.id == bookId } }

        override fun observeSyncState(profileId: ProfileId): Flow<SyncState> =
            MutableStateFlow(SyncState.idle(SERVER, profileId))

        override suspend fun refresh(profileId: ProfileId): AppResult<Int> = AppResult.Success(0)

        override suspend fun writeProgress(profileId: ProfileId, progress: List<AccountProgress>): AppResult<Int> =
            AppResult.Success(0)

        override suspend fun searchServer(profileId: ProfileId, query: String): AppResult<Int> = AppResult.Success(0)
    }

    private companion object {
        val SERVER = ServerId("srv_books")
        val ADA = ProfileId("prf_ada")
        val GRACE = ProfileId("prf_grace")
    }
}
