package com.example.shelfplayer.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.database.RoomDatabaseTransactionRunner
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibrarySnapshot
import com.example.shelfplayer.core.model.playback.ListeningSession
import com.example.shelfplayer.core.network.fake.FakeAudiobookshelfGateway
import com.example.shelfplayer.core.network.fixture.FixtureLibraryLoader
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.core.network.gateway.CachedLibrary
import com.example.shelfplayer.core.network.gateway.LibraryApi
import com.example.shelfplayer.core.network.gateway.PlaybackApi
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #41 — the recent-book hot set is an ordering optimisation inside the existing library refresh.
 *
 * These are repository/Room tests rather than UI tests because the contract under test is exactly that
 * Room becomes useful early while the same full refresh remains authoritative afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentBookHydrationTest {

    private lateinit var database: ShelfPlayerDatabase
    private lateinit var repository: DefaultLibraryRepository
    private lateinit var baseGateway: FakeAudiobookshelfGateway
    private lateinit var libraryApi: RecordingLibraryApi
    private lateinit var playbackApi: RecordingPlaybackApi
    private val profileId = ProfileId("fixture-profile")
    private val sink = RecordingLogSink()
    private val testScheduler = TestCoroutineScheduler()
    private val testDispatcher = UnconfinedTestDispatcher(testScheduler)

    @Before
    fun setUp() = runTest(testDispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val dispatcher = testDispatcher
        val logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default))
        baseGateway = FakeAudiobookshelfGateway(
            loader = FixtureLibraryLoader(),
            clock = TestAppClock(),
            logger = logger,
            ioDispatcher = dispatcher,
        )
        seedFixtureAccount()

        val snapshots = fixtureSnapshots()
        libraryApi = RecordingLibraryApi(baseGateway.library, snapshots)
        playbackApi = RecordingPlaybackApi(baseGateway.playback)
        val gateway = object : AudiobookshelfGateway by baseGateway {
            override val library: LibraryApi = libraryApi
            override val playback: PlaybackApi = playbackApi
        }
        val writer = LibrarySnapshotWriter(
            transaction = RoomDatabaseTransactionRunner(database),
            libraryWriteDao = database.libraryWriteDao(),
            progressDao = database.progressDao(),
            historyDao = database.playbackHistoryDao(),
        )
        repository = DefaultLibraryRepository(
            libraryDao = database.libraryDao(),
            profileDao = database.profileDao(),
            progressDao = database.progressDao(),
            syncStateDao = database.syncStateDao(),
            gateway = gateway,
            writer = writer,
            clock = TestAppClock(),
            logger = logger,
            ioDispatcher = dispatcher,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `recent sessions are bounded deduplicated hydrated early and skipped by bulk expansion`() {
        runTest(testDispatcher) {
            playbackApi.answer = AppResult.Success(
                listOf(
                    session(VOYAGE_ONE, "newest"),
                    session(VOYAGE_ONE, "duplicate"),
                    session("not-visible", "hidden"),
                    session(VOYAGE_TWO, "next"),
                ),
            )
            var earlyBookSeen = false
            libraryApi.afterCatalogue = { libraryId ->
                if (libraryId == FICTION && !earlyBookSeen) {
                    val early = repository.observeBook(profileId, LibraryItemId(VOYAGE_ONE)).first()
                    earlyBookSeen = early?.progress != null &&
                        database.libraryDao()
                            .expandedBookStamps(profileId.value, EntityKey.of(SERVER, FICTION.value))
                            .any { it.remoteId == VOYAGE_ONE }
                }
            }

            val result = repository.refresh(profileId)

            assertIs<AppResult.Success<Int>>(result)
            assertEquals(listOf(0 to 10), playbackApi.calls, "one captured-size history page")
            assertEquals(
                listOf(VOYAGE_ONE, VOYAGE_TWO),
                libraryApi.targetedFetches,
                "first occurrence wins and catalogue-invisible ids never become targeted requests",
            )
            assertTrue(earlyBookSeen, "targeted metadata/progress must reach Room before the bulk expansion callback")
            assertFalse(VOYAGE_ONE in libraryApi.bulkExpandedIds)
            assertFalse(VOYAGE_TWO in libraryApi.bulkExpandedIds)
            assertNull(repository.observeBook(profileId, LibraryItemId("not-visible")).first())
            assertEquals(7, repository.observeAccessibleBooks(profileId).first().size)
        }
    }

    @Test
    fun `already-current recent candidates never make a targeted request`() = runTest(testDispatcher) {
        repository.refresh(profileId)
        libraryApi.clearRecording()
        playbackApi.clearRecording()
        playbackApi.answer = AppResult.Success(listOf(session(VOYAGE_ONE, "current")))

        val result = repository.refresh(profileId)

        assertIs<AppResult.Success<Int>>(result)
        assertTrue(libraryApi.targetedFetches.isEmpty())
        assertTrue(libraryApi.bulkExpandedIds.isEmpty(), "the same stored revision also skips ordinary expansion")
    }

    @Test
    fun `local unsynced progress survives targeted hydration and the completed refresh`() = runTest(testDispatcher) {
        repository.refresh(profileId)
        val bookKey = EntityKey.of(SERVER, VOYAGE_ONE)
        val local = assertNotNull(database.progressDao().findProgress(profileId.value, bookKey))
        database.progressDao().upsertProgress(
            listOf(
                local.copy(
                    positionMillis = LOCAL_POSITION_MS,
                    updatedAt = local.updatedAt + 1,
                    hasUnsyncedChanges = true,
                ),
            ),
        )
        database.libraryWriteDao().deleteTracksFor(bookKey)
        libraryApi.clearRecording()
        playbackApi.clearRecording()
        playbackApi.answer = AppResult.Success(listOf(session(VOYAGE_ONE, "remote")))

        val result = repository.refresh(profileId)

        assertIs<AppResult.Success<Int>>(result)
        assertEquals(listOf(VOYAGE_ONE), libraryApi.targetedFetches)
        assertFalse(VOYAGE_ONE in libraryApi.bulkExpandedIds, "the hot fetch stored the catalogue revision")
        val after = assertNotNull(database.progressDao().findProgress(profileId.value, bookKey))
        assertEquals(LOCAL_POSITION_MS, after.positionMillis)
        assertTrue(after.hasUnsyncedChanges)
    }

    @Test
    fun `history failure is isolated and the normal full refresh still completes`() = runTest(testDispatcher) {
        playbackApi.answer = AppResult.Failure(AppError.Network())

        val result = repository.refresh(profileId)

        assertIs<AppResult.Success<Int>>(result)
        assertEquals(listOf(0 to 10), playbackApi.calls)
        assertTrue(libraryApi.targetedFetches.isEmpty())
        assertEquals(7, repository.observeAccessibleBooks(profileId).first().size)
    }

    @Test
    fun `targeted hydration failure falls through to ordinary bulk expansion`() = runTest(testDispatcher) {
        playbackApi.answer = AppResult.Success(listOf(session(VOYAGE_ONE, "recent")))
        libraryApi.failedTargetedFetches += VOYAGE_ONE

        val result = repository.refresh(profileId)

        assertIs<AppResult.Success<Int>>(result)
        assertEquals(listOf(VOYAGE_ONE), libraryApi.targetedFetches)
        assertTrue(VOYAGE_ONE in libraryApi.bulkExpandedIds)
        assertNotNull(repository.observeBook(profileId, LibraryItemId(VOYAGE_ONE)).first())
    }

    @Test
    fun `hot set remains capped even if a server overfills the requested history page`() = runTest(testDispatcher) {
        playbackApi.answer = AppResult.Success(
            (1..10).map { session("hidden-$it", "s-$it") } + session(VOYAGE_ONE, "eleventh"),
        )

        repository.refresh(profileId)

        assertEquals(listOf(0 to 10), playbackApi.calls)
        assertTrue(libraryApi.targetedFetches.isEmpty(), "the eleventh unique id is outside the bounded hot set")
    }

    @Test
    fun `catalogue admission blocks inaccessible libraries and tag-filtered candidates`() = runTest(testDispatcher) {
        database.profileDao().setAccountState(
            profileId = profileId.value,
            role = "Listener",
            accessibleLibrariesJson = """["lib-fiction"]""",
            hasAllLibraryAccess = false,
            hasAllTagAccess = false,
            canDownload = false,
        )
        libraryApi.listedLibraryIds = setOf(FICTION)
        libraryApi.catalogueExcludedIds += VOYAGE_TWO
        playbackApi.answer = AppResult.Success(
            listOf(
                session(QUIET_ONE, "wrong-library"),
                session(VOYAGE_TWO, "tag-filtered"),
                session(VOYAGE_ONE, "allowed"),
            ),
        )

        repository.refresh(profileId)

        assertEquals(listOf(VOYAGE_ONE), libraryApi.targetedFetches)
        assertNull(repository.observeBook(profileId, LibraryItemId(QUIET_ONE)).first())
        assertNull(repository.observeBook(profileId, LibraryItemId(VOYAGE_TWO)).first())
        assertNotNull(repository.observeBook(profileId, LibraryItemId(VOYAGE_ONE)).first())
    }

    private suspend fun fixtureSnapshots(): Map<LibraryId, List<BookSnapshot>> {
        val libraries = assertIs<AppResult.Success<List<Library>>>(baseGateway.library.listLibraries(profileId)).value
        return libraries.associate { library ->
            val snapshot = assertIs<AppResult.Success<LibrarySnapshot>>(
                baseGateway.library.listBooks(profileId, library.id),
            ).value
            library.id to snapshot.books
        }
    }

    private suspend fun seedFixtureAccount() {
        database.profileDao().upsertServer(
            ServerEntity(
                serverId = SERVER,
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
                profileId = profileId.value,
                serverId = SERVER,
                remoteUserId = null,
                username = "demo",
                displayName = "Demo listener",
                role = "Listener",
                requiresReauthentication = false,
                lastUsedAt = null,
                isFixture = true,
                accessibleLibrariesJson = "[]",
                hasAllLibraryAccess = true,
                hasAllTagAccess = true,
                canDownload = false,
            ),
        )
    }

    private fun session(bookId: String, id: String) = ListeningSession(
        id = id,
        bookId = LibraryItemId(bookId),
        deviceId = "other-device",
        deviceName = null,
        clientName = null,
        listened = 1.seconds,
        startedFrom = Duration.ZERO,
        reachedAt = 1.seconds,
        startedAt = Instant.EPOCH,
    )

    private class RecordingPlaybackApi(private val delegate: PlaybackApi) : PlaybackApi by delegate {
        var answer: AppResult<List<ListeningSession>> = AppResult.Success(emptyList())
        val calls = mutableListOf<Pair<Int, Int>>()

        override suspend fun listeningSessions(
            profileId: ProfileId,
            page: Int,
            itemsPerPage: Int,
        ): AppResult<List<ListeningSession>> {
            calls += page to itemsPerPage
            return answer
        }

        fun clearRecording() {
            calls.clear()
        }
    }

    private class RecordingLibraryApi(
        private val delegate: LibraryApi,
        snapshots: Map<LibraryId, List<BookSnapshot>>,
    ) : LibraryApi by delegate {
        private val byLibrary = snapshots
        private val byId = snapshots.values.flatten().associateBy { it.book.id.value }

        val targetedFetches = mutableListOf<String>()
        val bulkExpandedIds = mutableListOf<String>()
        val failedTargetedFetches = mutableSetOf<String>()
        val catalogueExcludedIds = mutableSetOf<String>()
        var listedLibraryIds: Set<LibraryId>? = null
        var afterCatalogue: (suspend (LibraryId) -> Unit)? = null

        override suspend fun listLibraries(profileId: ProfileId): AppResult<List<Library>> =
            when (val result = delegate.listLibraries(profileId)) {
                is AppResult.Failure -> result

                is AppResult.Success -> AppResult.Success(
                    result.value.filter { library -> listedLibraryIds?.contains(library.id) ?: true },
                )
            }

        override suspend fun fetchBook(profileId: ProfileId, bookId: LibraryItemId): AppResult<BookSnapshot> {
            targetedFetches += bookId.value
            if (bookId.value in failedTargetedFetches) return AppResult.Failure(AppError.Network())
            return byId[bookId.value]?.let { snapshot -> AppResult.Success(snapshot) }
                ?: AppResult.Failure(AppError.Server(statusCode = 404))
        }

        override suspend fun listBooks(
            profileId: ProfileId,
            libraryId: LibraryId,
            onBatch: suspend (List<BookSnapshot>) -> Unit,
            cached: CachedLibrary,
            onCatalogueBatch: suspend (List<BookSnapshot>) -> Unit,
        ): AppResult<LibrarySnapshot> {
            val catalogue = byLibrary[libraryId].orEmpty()
                .filterNot { it.book.id.value in catalogueExcludedIds }
            onCatalogueBatch(catalogue)
            afterCatalogue?.invoke(libraryId)

            val toExpand = catalogue.filterNot { snapshot ->
                cached.isUpToDate(snapshot.book.id, snapshot.book.remoteUpdatedAt?.toEpochMilli())
            }
            val (started, rest) = toExpand.partition { cached.isInProgress(it.book.id) }
            val expanded = started + rest
            bulkExpandedIds += expanded.map { it.book.id.value }
            if (expanded.isNotEmpty()) onBatch(expanded)
            return AppResult.Success(
                LibrarySnapshot(
                    books = expanded,
                    visibleIds = catalogue.map { it.book.id },
                ),
            )
        }

        fun clearRecording() {
            targetedFetches.clear()
            bulkExpandedIds.clear()
            failedTargetedFetches.clear()
            catalogueExcludedIds.clear()
            listedLibraryIds = null
            afterCatalogue = null
        }
    }

    private companion object {
        const val SERVER = "fixture-server"
        val FICTION = LibraryId("lib-fiction")
        const val VOYAGE_ONE = "book-voyage-1"
        const val VOYAGE_TWO = "book-voyage-2"
        const val QUIET_ONE = "book-quiet-1"
        const val LOCAL_POSITION_MS = 987_654L
    }
}
