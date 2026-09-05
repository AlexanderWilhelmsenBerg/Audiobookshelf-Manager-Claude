package com.example.shelfplayer.playback

import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.auth.AccountProgress
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.model.library.MediaProgress
import com.example.shelfplayer.core.model.library.Series
import com.example.shelfplayer.core.model.library.SeriesMembership
import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.PlaybackHistoryEntry
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.ObserveHomeShelvesUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** PRODUCT_SPEC PLAY-001 / 11.1 — the stable, audiobook-first tree Android Auto receives. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutoBrowseTreeTest {

    private val books = MutableStateFlow<List<Book>>(emptyList())
    private val chapters = MutableStateFlow<List<Chapter>>(emptyList())

    @Test
    fun `a non-empty library always exposes exactly four learned root destinations`() = runTest {
        books.value = listOf(book("book-1", "The Salt Harbour"))

        val root = auto().children(AutoLibrary.ROOT, now = null)

        assertEquals(
            listOf(
                AutoLibrary.TAB_CONTINUE,
                AutoLibrary.TAB_CHAPTERS,
                AutoLibrary.TAB_HISTORY,
                AutoLibrary.TAB_LIBRARY,
            ),
            root.map { it.mediaId },
        )
        assertEquals(listOf("Continue", "Chapters", "History", "Library"), root.titles())
    }

    @Test
    fun `continue remains a stable root even before the first book is started`() = runTest {
        books.value = listOf(book("book-1", "The Salt Harbour"))

        val root = auto().children(AutoLibrary.ROOT, now = null)

        assertTrue(root.any { it.mediaId == AutoLibrary.TAB_CONTINUE })
        assertTrue(auto().children(AutoLibrary.TAB_CONTINUE, now = null).isEmpty())
    }

    @Test
    fun `an empty library explains itself instead of exposing four empty tabs`() = runTest {
        val rows = auto().children(AutoLibrary.ROOT, now = null)

        assertEquals(1, rows.size)
        assertEquals(AutoLibrary.NOTICE_EMPTY, rows.single().mediaId)
        assertFalse(rows.single().mediaMetadata.isPlayable == true)
        assertEquals("No books yet", rows.single().mediaMetadata.title?.toString())
    }

    @Test
    fun `library holds broad discovery rather than spending root positions on it`() = runTest {
        books.value = listOf(
            book(
                id = "book-1",
                title = "The Salt Harbour",
                series = membership("series-1", "Tidewatch", "1"),
                local = LocalAvailability.Complete,
            ),
        )

        val rootIds = auto().children(AutoLibrary.ROOT, now = null).map { it.mediaId }
        val libraryIds = auto().children(AutoLibrary.TAB_LIBRARY, now = null).map { it.mediaId }

        assertTrue(AutoLibrary.TAB_SERIES in libraryIds)
        assertTrue(AutoLibrary.TAB_AUTHORS in libraryIds)
        assertTrue(AutoLibrary.TAB_DOWNLOADS in libraryIds)
        assertTrue(AutoLibrary.TAB_DISCOVER in libraryIds)
        assertTrue(AutoLibrary.TAB_OUTPUT in libraryIds)
        assertFalse(AutoLibrary.TAB_DISCOVER in rootIds)
        assertFalse(AutoLibrary.TAB_OUTPUT in rootIds)
    }

    @Test
    fun `series books use the domain numeric sequence order`() = runTest {
        val ten = membership("series-1", "Tidewatch", "10")
        val two = membership("series-1", "Tidewatch", "2")
        books.value = listOf(
            book("book-10", "Tenth", series = ten),
            book("book-2", "Second", series = two),
        )

        val series = auto().children(AutoLibrary.TAB_SERIES, now = null).single()
        val rows = auto().children(series.mediaId, now = null)

        assertEquals(listOf("Second", "Tenth"), rows.titles())
    }

    @Test
    fun `voice search includes series names`() = runTest {
        books.value = listOf(
            book("book-1", "The Salt Harbour", series = membership("series-1", "Tidewatch", "1")),
            book("book-2", "Unrelated"),
        )

        assertEquals(listOf("The Salt Harbour"), auto().search("tidewatch").titles())
    }

    @Test
    fun `the output browse node never exposes the phone speaker`() = runTest {
        books.value = listOf(book("book-1", "The Salt Harbour"))
        val outputs = FakeAutoOutputs.of(
            FakeAutoOutputs.output("bluetooth:buds", "Buds"),
            FakeAutoOutputs.output("speaker:phone", "Phone speaker", DeviceKind.Speaker),
        )

        val rows = auto(outputs).children(AutoLibrary.TAB_OUTPUT, now = null)

        assertEquals(listOf("Automatic", "Buds"), rows.titles())
        assertTrue(rows.none { it.mediaId.contains("speaker:phone") })
    }

    @Test
    fun `a stale cached phone-speaker row is refused`() = runTest {
        books.value = listOf(book("book-1", "The Salt Harbour"))
        val outputs = FakeAutoOutputs.of(
            FakeAutoOutputs.output("bluetooth:buds", "Buds"),
            FakeAutoOutputs.output("speaker:phone", "Phone speaker", DeviceKind.Speaker),
        )

        val rows = auto(outputs).children("${AutoLibrary.OUT_PREFIX}speaker:phone", now = null)

        assertTrue(outputs.chosen.isEmpty())
        assertNull(outputs.selected())
        assertEquals(listOf("No selectable audio outputs found"), rows.titles())
    }

    @Test
    fun `the automatic row releases the preferred route`() = runTest {
        books.value = listOf(book("book-1", "The Salt Harbour"))
        val outputs = FakeAutoOutputs.of(
            FakeAutoOutputs.output("bluetooth:buds", "Buds"),
            selected = "bluetooth:buds",
        )

        auto(outputs).children("${AutoLibrary.OUT_PREFIX}${AutoLibrary.AUTOMATIC_OUTPUT}", now = null)

        assertEquals(listOf<String?>(null), outputs.chosen)
        assertNull(outputs.selected())
    }

    @Test
    fun `the resumable item keeps the stored position and richer author-series subtitle`() = runTest {
        books.value = listOf(
            book(
                "book-1",
                "The Salt Harbour",
                progress = progress(40.minutes, false),
                series = membership("series-1", "Tidewatch", "2"),
            ),
        )

        val item = auto().resumeItem()

        assertEquals("at/book-1/${40.minutes.inWholeMilliseconds}", item?.mediaId)
        assertEquals("Marisol Holt · Tidewatch #2", item?.mediaMetadata?.subtitle?.toString())
    }

    /**
     * The nodes a car reaches by drilling in are parents too, and Media3 does not invalidate descendants.
     *
     * Notifying only the fixed tabs left a head unit that had opened a series or an author still showing
     * the **previous profile's** books after a switch — a profile boundary, not stale UI. Asserted from the
     * ids the tree actually emitted rather than a literal, so a node kind added to `seriesNodes`/
     * `authorNodes` without being remembered fails here.
     */
    @Test
    fun `series and author nodes handed to the car are invalidated too`() = runTest {
        books.value = listOf(
            book("book-1", "The Salt Harbour", series = membership("series-1", "Tidewatch", "2")),
        )
        val auto = auto()

        assertFalse(
            auto.browsableParents().any { it.startsWith("series/") || it.startsWith("author/") },
            "nothing has been handed out yet",
        )

        val emitted = (auto.children(AutoLibrary.TAB_SERIES, null) + auto.children(AutoLibrary.TAB_AUTHORS, null))
            .map { it.mediaId }
        val parents = auto.browsableParents()

        assertTrue("series/series-1" in emitted && "author/author-1" in emitted, "the tree offered both nodes")
        emitted.forEach { id -> assertTrue(id in parents, "$id was handed to the car and must be invalidated") }
        assertEquals(parents.size, parents.distinct().size, "a parent must not be notified twice")
    }

    /** `SeriesSequence.Absent.raw` is empty, and a bare "#" reads as missing data rather than no sequence. */
    @Test
    fun `a series with no sequence is named without a dangling marker`() = runTest {
        books.value = listOf(
            book("book-1", "The Salt Harbour", series = membership("series-1", "Tidewatch", "")),
        )

        val subtitle = auto().children(AutoLibrary.TAB_AUTHORS, null)
            .let { auto().children("author/author-1", null) }
            .first().mediaMetadata.subtitle?.toString()

        assertEquals("Marisol Holt · Tidewatch", subtitle)
    }

    private fun List<androidx.media3.common.MediaItem>.titles(): List<String> =
        mapNotNull { it.mediaMetadata.title?.toString() }

    private fun auto(outputs: AutoLibrary.Outputs = FakeAutoOutputs()): AutoLibrary {
        val profiles = StubProfiles()
        val library = StubLibrary(books, chapters)
        return AutoLibrary(
            context = ApplicationProvider.getApplicationContext(),
            profiles = profiles,
            library = library,
            history = StubHistory(),
            homeShelves = ObserveHomeShelvesUseCase(profiles, library, UnconfinedTestDispatcher()),
            audioOutputs = outputs,
        )
    }

    private fun progress(position: Duration, isFinished: Boolean, at: Long = 1_000) = MediaProgress(
        serverId = SERVER,
        profileId = PROFILE,
        bookId = LibraryItemId("book"),
        position = position,
        duration = 11.hours,
        isFinished = isFinished,
        updatedAt = Instant.ofEpochMilli(at),
        hasUnsyncedChanges = false,
    )

    private fun membership(id: String, name: String, sequence: String) = SeriesMembership(
        series = Series(SERVER, SeriesId(id), name),
        sequence = SeriesSequence.parse(sequence),
        isPrimary = true,
    )

    private fun book(
        id: String,
        title: String,
        progress: MediaProgress? = null,
        series: SeriesMembership? = null,
        local: LocalAvailability = LocalAvailability.NotDownloaded,
    ) = Book(
        serverId = SERVER,
        id = LibraryItemId(id),
        libraryId = LibraryId("lib-fiction"),
        title = title,
        subtitle = null,
        authors = listOf(Author(SERVER, AuthorId("author-1"), "Marisol Holt")),
        narrators = emptyList(),
        seriesMemberships = listOfNotNull(series),
        duration = 11.hours,
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
        addedAt = Instant.ofEpochMilli(1_000),
        lastFetchedAt = Instant.ofEpochMilli(0),
        progress = progress,
        localAvailability = local,
    )

    private class StubProfiles : ProfileRepository {
        private val profile = Profile(
            id = PROFILE,
            serverId = SERVER,
            username = "demo",
            displayName = "Demo listener",
            role = ProfileRole.Listener,
            requiresReauthentication = false,
            lastUsedAt = null,
            isFixture = false,
        )

        override fun observeProfiles(): Flow<List<Profile>> = flowOf(listOf(profile))
        override fun observeServers(): Flow<List<Server>> = flowOf(emptyList())
        override fun observeActiveProfile(): Flow<Profile?> = flowOf(profile)
        override suspend fun activeProfileId(): ProfileId = PROFILE
        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class StubLibrary(
        private val books: MutableStateFlow<List<Book>>,
        private val chapters: MutableStateFlow<List<Chapter>>,
    ) : LibraryRepository {
        override fun observeLibraries(profileId: ProfileId): Flow<List<Library>> = flowOf(emptyList())
        override fun observeLibrary(profileId: ProfileId, libraryId: LibraryId): Flow<Library?> = flowOf(null)
        override fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>> = books
        override fun observeAccessibleBooks(profileId: ProfileId): Flow<List<Book>> = books
        override fun observeBook(profileId: ProfileId, bookId: LibraryItemId): Flow<Book?> =
            flowOf(books.value.firstOrNull { it.id == bookId })
        override fun observeChapters(profileId: ProfileId, bookId: LibraryItemId): Flow<List<Chapter>> = chapters
        override fun observeSyncState(profileId: ProfileId): Flow<SyncState> = emptyFlow()
        override suspend fun refresh(profileId: ProfileId): AppResult<Int> = AppResult.Success(0)
        override suspend fun writeProgress(profileId: ProfileId, progress: List<AccountProgress>): AppResult<Int> =
            AppResult.Success(0)
        override suspend fun searchServer(profileId: ProfileId, query: String): AppResult<Int> = AppResult.Success(0)
    }

    private class StubHistory : PlaybackHistoryRepository {
        override fun observe(bookId: LibraryItemId, limit: Int): Flow<List<PlaybackHistoryEntry>> = flowOf(emptyList())
        override suspend fun record(
            bookId: LibraryItemId,
            event: PlaybackEvent,
            from: Duration?,
            to: Duration,
            detail: Duration?,
            at: Instant?,
            owner: ProfileId?,
        ) = Unit
        override suspend fun refreshServerSessions(bookId: LibraryItemId) = Unit
        override suspend fun clear(bookId: LibraryItemId) = Unit
    }

    private companion object {
        val SERVER = ServerId("server-1")
        val PROFILE = ProfileId("profile-1")
    }
}
