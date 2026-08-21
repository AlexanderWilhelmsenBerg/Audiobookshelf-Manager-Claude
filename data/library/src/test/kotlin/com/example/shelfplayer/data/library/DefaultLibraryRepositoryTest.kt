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
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibraryKind
import com.example.shelfplayer.core.model.library.LibrarySnapshot
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
import java.time.Instant
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
    private lateinit var writer: LibrarySnapshotWriter
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
        writer = LibrarySnapshotWriter(
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
            gateway = FakeAudiobookshelfGateway(
                loader = FixtureLibraryLoader(),
                clock = TestAppClock(),
                logger = logger,
                ioDispatcher = dispatcher,
            ),
            writer = writer,
            clock = TestAppClock(),
            logger = logger,
            ioDispatcher = dispatcher,
        )

        seedFixtureAccount()
    }

    /**
     * The server and profile every test reads through.
     *
     * Apart from [setUp] because a repository test's setup is *building the repository*; the account rows are
     * a precondition of the read path, not part of assembling it. Keeping them here also means adding a column
     * to [ProfileEntity] touches one small function rather than lengthening the one that wires eleven
     * collaborators together.
     */
    private suspend fun seedFixtureAccount() {
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
                hasAllTagAccess = true,
                canDownload = false,
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
                hasAllTagAccess = true,
                canDownload = false,
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

    /**
     * PRODUCT_SPEC LIB-001 — the first-stage catalogue is a preview, not a replacement.
     *
     * Minified rows deliberately carry no structured authors, series, tracks or chapters. Writing one
     * through the expanded-item path therefore erases a complete cached book on an unchanged refresh.
     */
    @Test
    fun `a catalogue batch preserves an already-expanded book`() = runTest {
        repository.refresh(fixtureProfile)
        val before = repository.observeBooks(fixtureProfile, LibraryId("lib-fiction")).first()
            .single { it.id.value == "book-voyage-1" }
        val library = fictionLibrary(before.serverId)

        writer.writeCatalogueBooks(
            profileId = fixtureProfile,
            library = library,
            books = listOf(
                BookSnapshot(
                    book = before.copy(authors = emptyList(), seriesMemberships = emptyList()),
                    tracks = emptyList(),
                    chapters = emptyList(),
                ),
            ),
        )

        val after = repository.observeBooks(fixtureProfile, LibraryId("lib-fiction")).first()
            .single { it.id.value == "book-voyage-1" }
        assertEquals(before.authors, after.authors)
        assertEquals(before.seriesMemberships, after.seriesMemberships)
        assertTrue(
            database.libraryDao().expandedBookStamps(
                fixtureProfile.value,
                EntityKey.of(before.serverId.value, before.libraryId.value),
            ).any { it.remoteId == before.id.value },
            "the catalogue preview must not remove the tracks that make the cached book expanded",
        )
    }

    /**
     * An unchanged refresh legitimately returns no expanded books: every detail request was skipped.
     * Reconciliation follows the unique catalogue-visible ids, not just the details fetched this run.
     */
    @Test
    fun `unchanged skipped books remain present after final reconciliation`() = runTest {
        repository.refresh(fixtureProfile)
        val before = repository.observeBooks(fixtureProfile, LibraryId("lib-fiction")).first()
        val library = fictionLibrary(before.first().serverId)

        writer.write(
            profileId = fixtureProfile,
            libraries = listOf(library),
            snapshots = mapOf(
                library.id to LibrarySnapshot(
                    books = emptyList(),
                    visibleIds = before.map { it.id } + before.first().id,
                ),
            ),
        )

        assertEquals(
            before.map { it.id }.toSet(),
            repository.observeBooks(fixtureProfile, LibraryId("lib-fiction")).first().map { it.id }.toSet(),
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
                hasAllTagAccess = true,
                canDownload = false,
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

        database.profileDao().setAccountState(
            profileId = fixtureProfile.value,
            role = "Listener",
            accessibleLibrariesJson = """["lib-nonfiction"]""",
            hasAllLibraryAccess = false,
            hasAllTagAccess = false,
            canDownload = false,
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
        database.profileDao().setAccountState(
            profileId = fixtureProfile.value,
            role = "Listener",
            accessibleLibrariesJson = "[]",
            hasAllLibraryAccess = false,
            hasAllTagAccess = false,
            canDownload = false,
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
                hasAllTagAccess = true,
                canDownload = false,
            ),
        )

        val forOther = repository.observeAccessibleBooks(ProfileId("other-profile")).first()
        assertTrue(forOther.all { it.progress == null })
    }

    /**
     * PRODUCT_SPEC LIB-001 / 13.2 — an incomplete sync writes, but never deletes.
     *
     * The reconciliation pass marks anything absent from a sync as deleted. That is correct when the sync
     * saw everything and wrong when it did not: an item missing because its fetch timed out would be
     * soft-deleted on the strength of a dropped connection, and the user would watch books disappear.
     */
    @Test
    fun `an incomplete sync keeps books it could not fetch`() = runTest {
        repository.refresh(fixtureProfile)
        val before = repository.observeBooks(fixtureProfile, LibraryId("lib-fiction")).first()
        assertEquals(5, before.size)

        // One book came back; the other four were unreachable. Written directly, because the fake gateway
        // has no way to make an individual item fail.
        writer.write(
            profileId = fixtureProfile,
            libraries = listOf(fictionLibrary(before.first().serverId)),
            snapshots = mapOf(
                LibraryId("lib-fiction") to LibrarySnapshot(
                    books = listOf(BookSnapshot(before.first(), tracks = emptyList(), chapters = emptyList())),
                    unreachableCount = 4,
                    // The catalogue still listed all five. Visibility follows the listing, not the fetch:
                    // an item whose expanded fetch timed out is one this account may see and this device
                    // could not reach, and those are not the same thing.
                    visibleIds = before.map { it.id },
                ),
            ),
        )

        assertEquals(
            5,
            repository.observeBooks(fixtureProfile, LibraryId("lib-fiction")).first().size,
            "an unreachable item is not a deleted one",
        )
    }

    /** The other half of the rule: a sync that saw everything *is* allowed to reconcile deletions. */
    @Test
    fun `a complete sync soft-deletes what the server no longer lists`() = runTest {
        repository.refresh(fixtureProfile)
        val before = repository.observeBooks(fixtureProfile, LibraryId("lib-fiction")).first()

        writer.write(
            profileId = fixtureProfile,
            libraries = listOf(fictionLibrary(before.first().serverId)),
            snapshots = mapOf(
                LibraryId("lib-fiction") to LibrarySnapshot(
                    books = listOf(BookSnapshot(before.first(), tracks = emptyList(), chapters = emptyList())),
                    removedCount = 4,
                    visibleIds = before.map { it.id },
                    removedIds = before.drop(1).map { it.id },
                ),
            ),
        )

        assertEquals(1, repository.observeBooks(fixtureProfile, LibraryId("lib-fiction")).first().size)
    }

    private fun fictionLibrary(serverId: ServerId) = Library(
        serverId = serverId,
        id = LibraryId("lib-fiction"),
        name = "Fiction",
        kind = LibraryKind.Book,
        displayOrder = 0,
        bookCount = 5,
        remoteUpdatedAt = null,
        lastFetchedAt = Instant.EPOCH,
    )

    /**
     * PRODUCT_SPEC 5.2 — a filtered account never deletes another account's books, and never sees them.
     *
     * The defect a device run found, and the worst one in the project so far: a restricted account synced
     * a shared library, the server served it 188 of 490 items, and reconciliation marked the other 302
     * removed — under the *unrestricted* account too. Absence from a filtered account's sync says
     * something about the filter, not about the server.
     *
     * The two halves are separate and both are asserted here, because fixing the first one alone is what
     * produced the second bug. Nothing may be **deleted**: the rows belong to whoever else can see them.
     * And the filtered profile's own view narrows to what it was served: the later device run found the
     * restricted account reading all 490 of the unrestricted account's books straight out of the shared
     * cache, online and offline alike.
     */
    @Test
    fun `a profile the server filters sees less without deleting anything`() = runTest {
        repository.refresh(fixtureProfile)
        val all = repository.observeBooks(fixtureProfile, LibraryId("lib-fiction")).first()
        assertEquals(5, all.size)
        val serverId = all.first().serverId

        writer.write(
            profileId = fixtureProfile,
            libraries = listOf(fictionLibrary(serverId)),
            snapshots = mapOf(
                LibraryId("lib-fiction") to LibrarySnapshot(
                    books = listOf(BookSnapshot(all.first(), tracks = emptyList(), chapters = emptyList())),
                ),
            ),
            reconciles = false,
        )

        assertEquals(
            0,
            database.libraryDao().observeBookCount(serverId.value, deleted = true).first(),
            "a filtered sync adds and updates; it never removes",
        )
        assertEquals(
            listOf(all.first().id),
            repository.observeBooks(fixtureProfile, LibraryId("lib-fiction")).first().map { it.id },
            "…and it shows this account only what the server was willing to list for it",
        )
    }

    /**
     * PRODUCT_SPEC 13.2 — a library the server no longer lists goes, and takes its books with it.
     *
     * Nothing enumerated libraries before, so deleting one on the server left a row that survived every
     * refresh. A device run called them "stale entries".
     */
    @Test
    fun `a library the server stopped listing is removed with its books`() = runTest {
        repository.refresh(fixtureProfile)
        assertEquals(2, repository.observeLibraries(fixtureProfile).first().size)
        val serverId = repository.observeBooks(fixtureProfile, LibraryId("lib-fiction")).first().first().serverId

        // The server now lists only Fiction. Non-fiction was deleted.
        writer.write(
            profileId = fixtureProfile,
            libraries = listOf(fictionLibrary(serverId)),
            snapshots = mapOf(LibraryId("lib-fiction") to LibrarySnapshot(books = emptyList())),
            reconciles = true,
        )

        assertEquals(listOf("Fiction"), repository.observeLibraries(fixtureProfile).first().map { it.name })
        assertTrue(
            repository.observeAccessibleBooks(fixtureProfile).first().none {
                it.libraryId == LibraryId("lib-nonfiction")
            },
            "the shelf reads books by server, so a deleted library's books have to go too",
        )
    }

    /** …and a filtered account must not be able to delete a library either. */
    @Test
    fun `a filtered profile does not remove libraries it was not shown`() = runTest {
        repository.refresh(fixtureProfile)
        val serverId = repository.observeBooks(fixtureProfile, LibraryId("lib-fiction")).first().first().serverId

        writer.write(
            profileId = fixtureProfile,
            libraries = listOf(fictionLibrary(serverId)),
            snapshots = mapOf(LibraryId("lib-fiction") to LibrarySnapshot(books = emptyList())),
            reconciles = false,
        )

        assertEquals(2, repository.observeLibraries(fixtureProfile).first().size)
    }

    /**
     * PRODUCT_SPEC LIB-002 — a server search reaches the shelf by being written, not returned.
     *
     * The count is the receipt; the books arrive on the Room stream the screen was already reading. That
     * is the property worth pinning, because a search that returned its own list would give the screen
     * two sources for the same book.
     */
    @Test
    fun `a server search writes its hits where the cached books are`() = runTest {
        val written = repository.searchServer(fixtureProfile, "Salt")

        assertEquals(AppResult.Success(1), written)
        val titles = repository.observeAccessibleBooks(fixtureProfile).first().map { it.title }
        assertTrue(titles.contains("The Salt Harbour"), "the hit is readable from Room: $titles")
    }

    /**
     * PRODUCT_SPEC LIB-002 — one character is not a search worth a request.
     *
     * The local predicate still narrows the shelf from the first keystroke. This governs only whether
     * the network is involved, and a single letter matches most of a library.
     */
    @Test
    fun `a one-character query never reaches the server`() = runTest {
        assertEquals(AppResult.Success(0), repository.searchServer(fixtureProfile, "S"))
        assertEquals(AppResult.Success(0), repository.searchServer(fixtureProfile, "  "))
    }

    /**
     * Product priority 2 — a search cannot erase a listening position.
     *
     * A search hit arrives with no `userMediaProgress`, so its snapshot carries `progress = null`. If
     * that null were written, searching for a book you are halfway through would rewind it.
     */
    @Test
    fun `a server search leaves an existing position alone`() = runTest {
        repository.refresh(fixtureProfile)
        val before = repository.observeAccessibleBooks(fixtureProfile).first()
            .first { it.title == "The Salt Harbour" }
            .progress

        repository.searchServer(fixtureProfile, "Salt")

        val after = repository.observeAccessibleBooks(fixtureProfile).first()
            .first { it.title == "The Salt Harbour" }
            .progress
        assertEquals(before, after)
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
