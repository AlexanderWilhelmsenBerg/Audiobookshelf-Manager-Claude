package com.example.shelfplayer.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
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
import com.example.shelfplayer.domain.lock.ProfileActivationGuard
import com.example.shelfplayer.domain.repository.LibraryRepository
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
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** PD-001 / PLAY-001 — the stable, artwork-first Android Auto browse tree. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutoBrowseTreeTest {

    private val books = MutableStateFlow<List<Book>>(emptyList())

    @Test
    fun `root always exposes exactly Continue Series Authors Profiles`() = runTest {
        books.value = listOf(book("book-1", "The Salt Harbour"))

        val root = auto().children(AutoLibrary.ROOT, now = null)

        assertEquals(
            listOf(
                AutoLibrary.TAB_CONTINUE,
                AutoLibrary.TAB_SERIES,
                AutoLibrary.TAB_AUTHORS,
                AutoLibrary.TAB_PROFILES,
            ),
            root.map { item -> item.mediaId },
        )
        assertEquals(listOf("Continue", "Series", "Authors", "Profiles"), root.titles())
    }

    @Test
    fun `empty library keeps the four roots so Profiles remains reachable`() = runTest {
        val root = auto().children(AutoLibrary.ROOT, now = null)

        assertEquals(
            listOf(
                AutoLibrary.TAB_CONTINUE,
                AutoLibrary.TAB_SERIES,
                AutoLibrary.TAB_AUTHORS,
                AutoLibrary.TAB_PROFILES,
            ),
            root.map { item -> item.mediaId },
        )
        assertTrue(auto().children(AutoLibrary.TAB_CONTINUE, now = null).isEmpty())
    }

    @Test
    fun `browse destinations request artwork grids while Profiles remains list oriented`() = runTest {
        val auto = auto()
        val root = auto.root()
        val tabs = auto.children(AutoLibrary.ROOT, now = null).associateBy { item -> item.mediaId }

        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM,
            root.mediaMetadata.extras?.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE),
        )
        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            tabs.getValue(AutoLibrary.TAB_CONTINUE).style(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE),
        )
        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            tabs.getValue(AutoLibrary.TAB_SERIES).style(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE),
        )
        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            tabs.getValue(AutoLibrary.TAB_AUTHORS).style(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE),
        )
        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            tabs.getValue(AutoLibrary.TAB_PROFILES).style(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE),
        )
    }

    @Test
    fun `series and author parents request grid cards for their books`() = runTest {
        books.value = listOf(
            book("book-1", "The Salt Harbour", series = membership("series-1", "Tidewatch", "1")),
        )
        val auto = auto()

        val series = auto.children(AutoLibrary.TAB_SERIES, now = null).single()
        val author = auto.children(AutoLibrary.TAB_AUTHORS, now = null).single()

        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            series.style(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE),
        )
        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            author.style(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE),
        )
    }

    /**
     * #174 — a profile row is an action. A browsable row made the host open a child view, and a profile has
     * no children, so selecting one showed an empty library instead of switching.
     */
    @Test
    fun `Profiles exposes every saved profile as an action row and never as a container`() = runTest {
        val rows = auto(StubProfiles(includeSecond = true))
            .children(AutoLibrary.TAB_PROFILES, now = null)

        assertEquals(listOf("profile/profile-1", "profile/profile-2"), rows.map { item -> item.mediaId })
        assertEquals(listOf("Demo listener", "Other listener"), rows.titles())
        assertTrue(rows.none { item -> item.mediaMetadata.isBrowsable == true }, "a host must not open a row")
        assertTrue(rows.all { item -> item.mediaMetadata.isPlayable == true }, "selecting a row is the switch")
        assertTrue(rows.all { item -> item.localConfiguration == null }, "a profile row carries no media URI")
        assertTrue(rows.first().mediaMetadata.subtitle?.toString().orEmpty().contains("Active profile"))
        assertTrue(rows.first().mediaMetadata.supportedCommands.isEmpty())
        assertEquals(
            listOf(AutoLibrary.ACTION_SWITCH_PROFILE),
            rows.last().mediaMetadata.supportedCommands,
        )
        assertEquals(
            listOf(PROFILE, ProfileId("profile-2")),
            rows.map { row -> AutoLibrary.profileSelectionOf(listOf(row)) },
            "selecting a row must name exactly that row's profile",
        )
    }

    @Test
    fun `only the active profile's row reads as already in use`() = runTest {
        val auto = auto(StubProfiles(includeSecond = true))

        assertTrue(auto.isActiveProfile(PROFILE))
        assertFalse(auto.isActiveProfile(ProfileId("profile-2")))
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
    fun `the resumable item keeps the stored position and distinct author and series fields`() = runTest {
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
        assertEquals("Tidewatch #2", item?.mediaMetadata?.subtitle?.toString())
        assertEquals("Marisol Holt", item?.mediaMetadata?.artist?.toString())
    }

    @Test
    fun `server progress supplies the resume item when this device has no remembered book`() = runTest {
        books.value = listOf(
            book("book-1", "Older", progress = progress(20.minutes, false, at = 1_000)),
            book("book-2", "Newer", progress = progress(30.minutes, false, at = 2_000)),
        )

        val item = auto(rememberedId = null).resumeItem()

        assertEquals("at/book-2/${30.minutes.inWholeMilliseconds}", item?.mediaId)
    }

    @Test
    fun `held resume metadata uses the bare book identity without becoming playable`() = runTest {
        books.value = listOf(
            book("book-1", "The Salt Harbour", progress = progress(40.minutes, false)),
        )

        val held = auto().heldResume()

        assertEquals("book-1", held?.item?.mediaId)
        assertEquals(40.minutes.inWholeMilliseconds, held?.startPositionMs)
        assertEquals(PROFILE, held?.item?.let(MediaItems::ownerOf))
        assertTrue(held?.item?.localConfiguration != null, "ExoPlayer needs an inert local media configuration")
        assertTrue(held?.item?.let(MediaItems::isResumePlaceholder) == true)
        assertTrue(held?.item?.let(MediaItems::isReadyToPlay) == false)
    }

    @Test
    fun `valid cached resume state does not request a server refresh`() = runTest {
        books.value = listOf(book("book-1", "Local", progress = progress(15.minutes, false)))
        val auto = auto()
        var refreshes = 0

        val result = auto.lastPlayedAfter { refreshes += 1 }

        assertEquals(0, refreshes)
        assertEquals(LibraryItemId("book-1"), result?.id)
    }

    @Test
    fun `server refresh is requested only when cached resume state is empty`() = runTest {
        books.value = listOf(book("book-1", "Untouched"))
        val auto = auto(rememberedId = null)
        var refreshes = 0

        val result = auto.lastPlayedAfter {
            refreshes += 1
            books.value = listOf(
                book("book-2", "From server", progress = progress(15.minutes, false, at = 3_000)),
            )
        }

        assertEquals(1, refreshes)
        assertEquals(LibraryItemId("book-2"), result?.id)
    }

    @Test
    fun `series and author nodes handed to the car are invalidated too`() = runTest {
        books.value = listOf(
            book("book-1", "The Salt Harbour", series = membership("series-1", "Tidewatch", "2")),
        )
        val auto = auto()

        assertFalse(
            auto.emittedDynamicParents().any { id -> id.startsWith("series/") || id.startsWith("author/") },
            "nothing has been handed out yet",
        )

        val emitted = (auto.children(AutoLibrary.TAB_SERIES, null) + auto.children(AutoLibrary.TAB_AUTHORS, null))
            .map { item -> item.mediaId }
        val remembered = auto.emittedDynamicParents()

        assertTrue("series/series-1" in emitted && "author/author-1" in emitted)
        emitted.forEach { id -> assertTrue(id in remembered, "$id must be invalidated after a profile switch") }
    }

    @Test
    fun `a series with no sequence is named without a dangling marker`() = runTest {
        books.value = listOf(
            book("book-1", "The Salt Harbour", series = membership("series-1", "Tidewatch", "")),
        )

        val subtitle = auto().children(AutoLibrary.TAB_AUTHORS, null)
            .let { auto().children("author/author-1", null) }
            .first().mediaMetadata.subtitle?.toString()

        assertEquals("Tidewatch", subtitle)
    }

    @Test
    fun `short standalone title has no invented series and keeps author as artist`() = runTest {
        books.value = listOf(book("book-1", "Short"))
        val row = auto().children("author/author-1", null).single()
        assertEquals("Short", row.mediaMetadata.title.toString())
        assertEquals(null, row.mediaMetadata.subtitle)
        assertEquals("Marisol Holt", row.mediaMetadata.artist.toString())
    }

    @Test
    fun `full long title and series sequence are distinct from the author in browse and resume`() = runTest {
        val title = "The Wonderful and Astonishingly Long Adventures of the Final Tidewatcher"
        books.value = listOf(
            book(
                "book-1",
                title,
                progress = progress(40.minutes, false),
                series = membership("series-1", "Tidewatch", "12.5"),
            ),
        )
        val auto = auto()
        val browse = auto.children("series/series-1", null).single()
        val resume = auto.resumeItem()
        for (row in listOfNotNull(browse, resume)) {
            assertEquals(title, row.mediaMetadata.title.toString())
            assertEquals("Tidewatch #12.5", row.mediaMetadata.subtitle.toString())
            assertEquals("Marisol Holt", row.mediaMetadata.artist.toString())
        }
    }

    @Test
    fun `missing series and missing author have no fake secondary metadata`() = runTest {
        books.value = listOf(book("book-1", "Standalone", authors = emptyList()))
        val row = auto().search("Standalone").single()
        assertEquals("Standalone", row.mediaMetadata.title.toString())
        assertEquals(null, row.mediaMetadata.subtitle)
        assertEquals(null, row.mediaMetadata.artist)
    }

    private fun androidx.media3.common.MediaItem.style(key: String): Int? = mediaMetadata.extras?.getInt(key)

    private fun List<androidx.media3.common.MediaItem>.titles(): List<String> =
        mapNotNull { item -> item.mediaMetadata.title?.toString() }

    private fun auto(
        profiles: ProfileRepository = StubProfiles(),
        rememberedId: LibraryItemId? = books.value.firstOrNull { book -> book.progress?.isFinished == false }?.id,
    ): AutoLibrary {
        val library = StubLibrary(books)
        return AutoLibrary(
            context = ApplicationProvider.getApplicationContext(),
            profiles = profiles,
            library = library,
            downloads = FakeAutoDownloads,
            rememberedBooks = FakeRememberedBooks(rememberedId, PROFILE),
            homeShelves = ObserveHomeShelvesUseCase(profiles, library, UnconfinedTestDispatcher()),
            activation = ProfileActivationGuard { true },
            artwork = AutoArtwork.None,
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
        authors: List<Author> = listOf(Author(SERVER, AuthorId("author-1"), "Marisol Holt")),
    ) = Book(
        serverId = SERVER,
        id = LibraryItemId(id),
        libraryId = LibraryId("lib-fiction"),
        title = title,
        subtitle = null,
        authors = authors,
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

    private class StubProfiles(includeSecond: Boolean = false) : ProfileRepository {
        private val active = Profile(
            id = PROFILE,
            serverId = SERVER,
            username = "demo",
            displayName = "Demo listener",
            role = ProfileRole.Listener,
            requiresReauthentication = false,
            lastUsedAt = null,
            isFixture = false,
        )
        private val saved = buildList {
            add(active)
            if (includeSecond) {
                add(
                    Profile(
                        id = ProfileId("profile-2"),
                        serverId = SERVER,
                        username = "other",
                        displayName = "Other listener",
                        role = ProfileRole.Listener,
                        requiresReauthentication = false,
                        lastUsedAt = null,
                        isFixture = false,
                    ),
                )
            }
        }

        override fun observeProfiles(): Flow<List<Profile>> = flowOf(saved)

        override fun observeServers(): Flow<List<Server>> = flowOf(emptyList())

        override fun observeActiveProfile(): Flow<Profile?> = flowOf(active)

        override suspend fun activeProfileId(): ProfileId = PROFILE

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class StubLibrary(private val books: MutableStateFlow<List<Book>>) : LibraryRepository {
        override fun observeLibraries(profileId: ProfileId): Flow<List<Library>> = flowOf(emptyList())

        override fun observeLibrary(profileId: ProfileId, libraryId: LibraryId): Flow<Library?> = flowOf(null)

        override fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>> = books

        override fun observeAccessibleBooks(profileId: ProfileId): Flow<List<Book>> = books

        override fun observeBook(profileId: ProfileId, bookId: LibraryItemId): Flow<Book?> =
            flowOf(books.value.firstOrNull { book -> book.id == bookId })

        override fun observeChapters(profileId: ProfileId, bookId: LibraryItemId): Flow<List<Chapter>> =
            flowOf(emptyList())

        override fun observeSyncState(profileId: ProfileId): Flow<SyncState> = emptyFlow()

        override suspend fun refresh(profileId: ProfileId): AppResult<Int> = AppResult.Success(0)

        override suspend fun writeProgress(profileId: ProfileId, progress: List<AccountProgress>): AppResult<Int> =
            AppResult.Success(0)

        override suspend fun searchServer(profileId: ProfileId, query: String): AppResult<Int> = AppResult.Success(0)
    }

    private companion object {
        val SERVER = ServerId("server-1")
        val PROFILE = ProfileId("profile-1")
    }
}
