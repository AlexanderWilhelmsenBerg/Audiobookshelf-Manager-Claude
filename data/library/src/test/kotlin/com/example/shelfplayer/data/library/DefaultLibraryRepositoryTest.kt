package com.example.shelfplayer.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.database.RoomDatabaseTransactionRunner
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.core.network.fake.FakeAudiobookshelfGateway
import com.example.shelfplayer.core.network.fixture.FixtureLibraryLoader
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Phase 0 exit criterion, executable: the fake gateway's library lands in Room and comes back
 * out as domain models (PRODUCT_SPEC 20, Phase 0 / LIB-001).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultLibraryRepositoryTest {

    private lateinit var database: ShelfPlayerDatabase
    private lateinit var repository: DefaultLibraryRepository
    private val sink = RecordingLogSink()
    private val fixtureProfile = ProfileId("fixture-profile")

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val dispatcher = UnconfinedTestDispatcher()
        val logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default))

        repository = DefaultLibraryRepository(
            libraryDao = database.libraryDao(),
            profileDao = database.profileDao(),
            progressDao = database.progressDao(),
            syncStateDao = database.syncStateDao(),
            gateway = FakeAudiobookshelfGateway(
                loader = FixtureLibraryLoader(),
                clock = TestAppClock(),
                logger = logger,
                ioDispatcher = dispatcher,
            ),
            writer = LibrarySnapshotWriter(
                transaction = RoomDatabaseTransactionRunner(database),
                libraryWriteDao = database.libraryWriteDao(),
                progressDao = database.progressDao(),
            ),
            clock = TestAppClock(),
            logger = logger,
            ioDispatcher = dispatcher,
        )

        database.profileDao().upsertServer(
            ServerEntity(
                serverId = "fixture-server",
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
                profileId = fixtureProfile.value,
                serverId = "fixture-server",
                remoteUserId = null,
                username = "demo",
                displayName = "Demo listener",
                role = "Listener",
                requiresReauthentication = false,
                lastUsedAt = null,
                isFixture = true,
                accessibleLibrariesJson = "[]",
                hasAllLibraryAccess = true,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `refresh writes the fixture library into Room`() = runTest {
        val result = repository.refresh(fixtureProfile)

        assertIs<AppResult.Success<Int>>(result)
        assertEquals(7, result.value)

        val libraries = repository.observeLibraries(fixtureProfile).first()
        assertEquals(listOf("Fiction", "Non-fiction"), libraries.map { it.name })
        assertEquals(5, libraries.first().bookCount)
    }

    @Test
    fun `books round-trip through Room with authors, series and narrators intact`() = runTest {
        repository.refresh(fixtureProfile)

        val books = repository.observeBooks(fixtureProfile, LibraryId("lib-fiction")).first()
        val salt = books.single { it.id.value == "book-voyage-1" }

        assertEquals("The Salt Harbour", salt.title)
        assertEquals(listOf("Marisol Holt"), salt.authors.map { it.name })
        assertEquals(listOf("Ada Fenwick"), salt.narrators)
        assertEquals(listOf("favourites"), salt.tags)
        assertEquals(
            SeriesSequence.Numeric("1", 1.0),
            salt.seriesMemberships.single().sequence,
        )
    }

    /** PRODUCT_SPEC 5.2 — progress belongs to the profile that recorded it. */
    @Test
    fun `progress is attached only for the requested profile`() = runTest {
        repository.refresh(fixtureProfile)

        val forFixture = repository.observeBook(fixtureProfile, LibraryItemId("book-voyage-1")).first()
        assertEquals(12_480_000L, forFixture?.progress?.position?.inWholeMilliseconds)

        database.profileDao().upsertProfile(
            ProfileEntity(
                profileId = "other-profile",
                serverId = "fixture-server",
                remoteUserId = null,
                username = "other",
                displayName = "Other",
                role = "Listener",
                requiresReauthentication = false,
                lastUsedAt = null,
                isFixture = true,
                accessibleLibrariesJson = "[]",
                hasAllLibraryAccess = true,
            ),
        )
        val forOther = repository
            .observeBook(ProfileId("other-profile"), LibraryItemId("book-voyage-1"))
            .first()

        assertEquals(null, forOther?.progress)
    }

    @Test
    fun `refresh is idempotent and does not duplicate rows`() = runTest {
        repository.refresh(fixtureProfile)
        val second = repository.refresh(fixtureProfile)

        assertIs<AppResult.Success<Int>>(second)
        assertEquals(
            5,
            repository.observeBooks(fixtureProfile, LibraryId("lib-fiction")).first().size,
        )
    }

    @Test
    fun `sync state records a successful refresh`() = runTest {
        repository.refresh(fixtureProfile)

        repository.observeSyncState(fixtureProfile).test {
            val state = awaitItem()
            assertEquals(SyncStatus.Succeeded, state.status)
            assertTrue(state.lastSuccessfulSyncAt != null)
            assertEquals(null, state.lastError)
        }
    }

    /**
     * PRODUCT_SPEC LIB-001 — a failed refresh leaves the cached library on screen.
     *
     * Asking for a profile the gateway does not serve is the cheapest way to produce a real gateway
     * failure without stubbing one.
     */
    @Test
    fun `a failed refresh keeps cached content and records the failure`() = runTest {
        repository.refresh(fixtureProfile)
        database.profileDao().upsertProfile(
            ProfileEntity(
                profileId = "stranger",
                serverId = "fixture-server",
                remoteUserId = null,
                username = "stranger",
                displayName = "Stranger",
                role = "Listener",
                requiresReauthentication = false,
                lastUsedAt = null,
                isFixture = false,
                accessibleLibrariesJson = "[]",
                hasAllLibraryAccess = true,
            ),
        )

        val failed = repository.refresh(ProfileId("stranger"))

        assertIs<AppResult.Failure>(failed)
        assertEquals(
            5,
            repository.observeBooks(fixtureProfile, LibraryId("lib-fiction")).first().size,
        )
        repository.observeSyncState(ProfileId("stranger")).test {
            assertEquals(SyncStatus.Failed, awaitItem().status)
        }
    }

    /** PRODUCT_SPEC LIB-002 — the shelf spans every library the profile is granted. */
    @Test
    fun `the accessible shelf returns the books of every granted library`() = runTest {
        repository.refresh(fixtureProfile)

        val shelf = repository.observeAccessibleBooks(fixtureProfile).first()

        assertEquals(7, shelf.size)
        assertEquals(
            setOf(LibraryId("lib-fiction"), LibraryId("lib-nonfiction")),
            shelf.map { it.libraryId }.toSet(),
        )
    }

    /**
     * PRODUCT_SPEC 5.2 and the Phase 1 exit criterion — unauthorized libraries never appear.
     *
     * The grant is enforced on write, so an unauthorized library is never synced in the first place. It
     * can also *shrink* afterwards, and nothing enumerates a library the server has stopped offering, so
     * its rows stay in the cache. This is the read-side half: the same rows, a narrower grant, and the
     * revoked library gone from every one of the four read paths.
     */
    @Test
    fun `a narrowed grant hides the revoked library from every read`() = runTest {
        repository.refresh(fixtureProfile)
        assertEquals(2, repository.observeLibraries(fixtureProfile).first().size)

        database.profileDao().setLibraryAccess(
            profileId = fixtureProfile.value,
            accessibleLibrariesJson = """["lib-nonfiction"]""",
            hasAllLibraryAccess = false,
        )

        assertEquals(
            listOf("Non-fiction"),
            repository.observeLibraries(fixtureProfile).first().map { it.name },
        )
        assertEquals(
            listOf(LibraryId("lib-nonfiction")),
            repository.observeAccessibleBooks(fixtureProfile).first().map { it.libraryId }.distinct(),
        )
        assertTrue(repository.observeBooks(fixtureProfile, LibraryId("lib-fiction")).first().isEmpty())
        assertNull(repository.observeLibrary(fixtureProfile, LibraryId("lib-fiction")).first())
        // The detail screen is a read path too: a direct route to a revoked book must not open it.
        assertNull(repository.observeBook(fixtureProfile, LibraryItemId("book-voyage-1")).first())
    }

    /**
     * A grant of no libraries at all is a real state, not an oversight.
     *
     * PRODUCT_SPEC 5.2's trap is the other direction — `accessAllLibraries` with an empty list means
     * *all* — so the empty-and-not-all case has to be proved to mean none.
     */
    @Test
    fun `a profile granted nothing sees nothing`() = runTest {
        repository.refresh(fixtureProfile)
        database.profileDao().setLibraryAccess(
            profileId = fixtureProfile.value,
            accessibleLibrariesJson = "[]",
            hasAllLibraryAccess = false,
        )

        assertEquals(emptyList(), repository.observeLibraries(fixtureProfile).first())
        assertEquals(emptyList(), repository.observeAccessibleBooks(fixtureProfile).first())
    }

    /** PRODUCT_SPEC 5.2 — the shelf carries the reading profile's progress and nobody else's. */
    @Test
    fun `the accessible shelf attaches only the requested profile's progress`() = runTest {
        repository.refresh(fixtureProfile)

        val played = repository.observeAccessibleBooks(fixtureProfile).first()
            .single { it.id.value == "book-voyage-1" }
        assertEquals(12_480_000L, played.progress?.position?.inWholeMilliseconds)

        database.profileDao().upsertProfile(
            ProfileEntity(
                profileId = "other-profile",
                serverId = "fixture-server",
                remoteUserId = null,
                username = "other",
                displayName = "Other",
                role = "Listener",
                requiresReauthentication = false,
                lastUsedAt = null,
                isFixture = true,
                accessibleLibrariesJson = "[]",
                hasAllLibraryAccess = true,
            ),
        )

        val forOther = repository.observeAccessibleBooks(ProfileId("other-profile")).first()
        assertTrue(forOther.all { it.progress == null })
    }

    /** PRODUCT_SPEC 14.5 — a sync log line never names a library or a book. */
    @Test
    fun `refresh logging contains no media titles`() = runTest {
        repository.refresh(fixtureProfile)

        assertFalse(sink.text.contains("Salt Harbour"))
        assertFalse(sink.text.contains("Fiction"))
        assertTrue(sink.text.contains("Library refresh completed"))
    }
}
