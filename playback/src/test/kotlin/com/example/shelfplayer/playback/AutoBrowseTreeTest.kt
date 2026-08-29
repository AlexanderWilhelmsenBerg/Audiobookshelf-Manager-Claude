package com.example.shelfplayer.playback

import androidx.test.core.app.ApplicationProvider
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
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.model.library.MediaProgress
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * PRODUCT_SPEC PLAY-001 / LIB-002 — **what a car actually shows**, which is the thing that shipped wrong.
 *
 * A device run found the car's browse screen saying "no books" while a voice search found the whole library.
 * The cause was not a filter: *Continue* was the only tab, and a library with nothing in progress has nothing
 * to put in it. Nothing tested the tree, because every existing test covered `resolve` and `root()` — the two
 * parts that need no repositories.
 *
 * So these tests are about the **tabs a driver is offered**, from a library in a stated condition. The most
 * important one is [a library nobody has started still offers something to play].
 */
/*
 * Robolectric, because the tab titles are resources now.
 *
 * They were Kotlin literals until the car was found showing "ShelfPlayer" three phases after the rename.
 * Resolving a string needs a real `Context`, and asserting on the resolved text is what makes a missing or
 * misnamed resource fail here rather than in a car.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutoBrowseTreeTest {

    private val books = MutableStateFlow<List<Book>>(emptyList())
    private val chapters = MutableStateFlow<List<Chapter>>(emptyList())

    /**
     * The one case the owner hit: a full library, nothing played yet.
     *
     * Before this change the root was Continue/Chapters/History and all three were empty, so the car said "no
     * books" about a library of thousands. *Discover* is the shelf that answers it, and it is the phone's own
     * shelf rather than a second implementation.
     */
    @Test
    fun `a library nobody has started still offers something to play`() = runTest {
        books.value = listOf(book("book-1", "The Salt Harbour"), book("book-2", "Tide Tables"))

        val tabs = auto().children(AutoLibrary.ROOT, now = null)

        assertTrue(AutoLibrary.TAB_DISCOVER in tabs.mediaIds(), "Discover is offered: ${tabs.titles()}")
        assertTrue(AutoLibrary.TAB_CONTINUE !in tabs.mediaIds(), "and Continue is not, because it is empty")
        val discovered = auto().children(AutoLibrary.TAB_DISCOVER, now = null)
        assertEquals(setOf("The Salt Harbour", "Tide Tables"), discovered.titles().toSet())
    }

    /** A part-played book puts Continue back, first, as it is on the phone. */
    @Test
    fun `a started book puts continue first`() = runTest {
        books.value = listOf(
            book("book-1", "The Salt Harbour", progress(position = 40.minutes, isFinished = false)),
            book("book-2", "Tide Tables"),
        )

        val tabs = auto().children(AutoLibrary.ROOT, now = null)

        assertEquals(AutoLibrary.TAB_CONTINUE, tabs.mediaIds().first())
        assertEquals(
            listOf("The Salt Harbour"),
            auto().children(AutoLibrary.TAB_CONTINUE, now = null).titles(),
        )
    }

    /** A finished book is offered again rather than disappearing, which is the phone's rule too. */
    @Test
    fun `a finished book appears under listen again and not under continue`() = runTest {
        books.value = listOf(book("book-1", "The Salt Harbour", progress(position = 11.hours, isFinished = true)))

        val tabs = auto().children(AutoLibrary.ROOT, now = null)

        assertTrue(AutoLibrary.TAB_AGAIN in tabs.mediaIds(), "Listen again is offered: ${tabs.titles()}")
        assertTrue(AutoLibrary.TAB_CONTINUE !in tabs.mediaIds())
        assertEquals(
            listOf("The Salt Harbour"),
            auto().children(AutoLibrary.TAB_AGAIN, now = null).titles(),
        )
    }

    /**
     * Chapters and History are always offered, unlike the shelves.
     *
     * They are about whatever is playing rather than about the library, so their emptiness is a state rather
     * than an absence — and a driver who wants to jump a chapter should not have to start something first to
     * discover the tab exists.
     */
    @Test
    fun `chapters and history are offered even when the library has shelves`() = runTest {
        books.value = listOf(book("book-1", "The Salt Harbour"))

        val ids = auto().children(AutoLibrary.ROOT, now = null).mediaIds()

        assertTrue(AutoLibrary.TAB_CHAPTERS in ids)
        assertTrue(AutoLibrary.TAB_HISTORY in ids)
    }

    /**
     * PRODUCT_SPEC 21 — an empty library says why, rather than showing a blank screen.
     *
     * A blank browse screen in a car is indistinguishable from a broken app, and "no books" — which is what
     * the owner saw — explains nothing. The row is unplayable, so tapping it cannot start anything.
     */
    @Test
    fun `an empty library explains itself in one unplayable row`() = runTest {
        books.value = emptyList()

        val children = auto().children(AutoLibrary.ROOT, now = null)

        assertEquals(1, children.size, "one row, not two tabs about nothing")
        assertEquals(AutoLibrary.NOTICE_EMPTY, children.single().mediaId)
        assertEquals(false, children.single().mediaMetadata.isPlayable)
        assertEquals("No books yet", children.single().mediaMetadata.title?.toString())
    }

    /**
     * ROUTE-001 — a media button resumes a *played* book, so a never-started library offers nothing.
     *
     * The regression this guards against is sharing one list between the Continue shelf and the resume target.
     * The shelf may reasonably widen; a headset press must not start a book at random because the library
     * happens to have one.
     */
    @Test
    fun `a never-started library has nothing for a media button to resume`() = runTest {
        books.value = listOf(book("book-1", "The Salt Harbour"), book("book-2", "Tide Tables"))

        assertEquals(null, auto().lastPlayed())
    }

    /** And with something played, it is the most recently played unfinished book. */
    @Test
    fun `a media button resumes the most recently played unfinished book`() = runTest {
        books.value = listOf(
            book("book-1", "The Salt Harbour", progress(40.minutes, isFinished = false, at = 1_000)),
            book("book-2", "Tide Tables", progress(10.minutes, isFinished = false, at = 9_000)),
            book("book-3", "Soundings", progress(11.hours, isFinished = true, at = 99_000)),
        )

        assertEquals("Tide Tables", auto().lastPlayed()?.title)
    }

    private fun List<androidx.media3.common.MediaItem>.mediaIds(): List<String> = mapNotNull { it.mediaId }

    private fun List<androidx.media3.common.MediaItem>.titles(): List<String> =
        mapNotNull { it.mediaMetadata.title?.toString() }

    /**
     * PRODUCT_SPEC PLAY-002 — the output tab is there whatever is connected.
     *
     * It used to appear only above two outputs. A car run retired that rule: the head unit is a connected
     * output and frequently the only one the platform reports, so the tab vanished in exactly the place a
     * driver would reach for it. One output is still a choice — the choice to move the book somewhere else.
     */
    @Test
    fun `the output tab is there whatever is connected`() = runTest {
        // A library with something in it. An empty one answers with the "no books" notice and nothing else,
        // which is deliberate: with no book to play there is no route to choose.
        books.value = listOf(book("book-1", "The Salt Harbour"))
        val none = auto(FakeAutoOutputs.of())
        val one = auto(FakeAutoOutputs.of(FakeAutoOutputs.output("car:car", "Car", DeviceKind.Car)))

        assertTrue(none.children(AutoLibrary.ROOT, now = null).any { it.mediaId == AutoLibrary.TAB_OUTPUT })
        assertTrue(one.children(AutoLibrary.ROOT, now = null).any { it.mediaId == AutoLibrary.TAB_OUTPUT })
    }

    /** With nothing connected the node says so, rather than being an empty screen a driver has to back out of. */
    @Test
    fun `an empty output tab explains itself`() = runTest {
        books.value = listOf(book("book-1", "The Salt Harbour"))

        val rows = auto(FakeAutoOutputs.of()).children(AutoLibrary.TAB_OUTPUT, now = null)

        assertEquals(listOf(AutoLibrary.NOTICE_OUTPUT), rows.map { it.mediaId })
    }

    /** Every row is browsable, because opening one must not go near the player. */
    @Test
    fun `output rows are browsable so choosing one never touches the player`() = runTest {
        val outputs = FakeAutoOutputs.of(
            FakeAutoOutputs.output("bluetooth:buds", "Buds"),
            FakeAutoOutputs.output("speaker:phone", "Phone speaker", DeviceKind.Speaker),
        )

        val rows = auto(outputs).children(AutoLibrary.TAB_OUTPUT, now = null)

        assertTrue(rows.isNotEmpty())
        rows.forEach { row ->
            assertTrue(row.mediaMetadata.isBrowsable == true, "${row.mediaId} must be browsable")
            assertTrue(row.mediaMetadata.isPlayable == false, "${row.mediaId} must not be playable")
        }
        // `AutoLibrary.resolve` decides what gets *played*; an output row must never be a play target.
        rows.forEach { row -> assertNull(AutoLibrary.resolve(row.mediaId)) }
    }

    /** Opening a row is choosing it. There is no second tap, so the tap that exists has to act. */
    @Test
    fun `opening an output row selects that output`() = runTest {
        val outputs = FakeAutoOutputs.of(
            FakeAutoOutputs.output("bluetooth:buds", "Buds"),
            FakeAutoOutputs.output("speaker:phone", "Phone speaker", DeviceKind.Speaker),
        )

        auto(outputs).children("${AutoLibrary.OUT_PREFIX}bluetooth:buds", now = null)

        assertEquals(listOf<String?>("bluetooth:buds"), outputs.chosen)
        assertEquals<String?>("bluetooth:buds", outputs.selected())
    }

    /** The *Automatic* row hands the decision back to the system, which is a `null` selection. */
    @Test
    fun `opening the automatic row clears the selection`() = runTest {
        val outputs = FakeAutoOutputs.of(
            FakeAutoOutputs.output("bluetooth:buds", "Buds"),
            FakeAutoOutputs.output("speaker:phone", "Phone", DeviceKind.Speaker),
            selected = "bluetooth:buds",
        )

        auto(outputs).children("${AutoLibrary.OUT_PREFIX}${AutoLibrary.AUTOMATIC_OUTPUT}", now = null)

        assertEquals(listOf<String?>(null), outputs.chosen)
        assertNull(outputs.selected())
    }

    /**
     * A car serves a cached browse list. A row naming a device that has since disconnected must change
     * nothing rather than routing somewhere arbitrary — the router refuses, and the tree reports Automatic.
     */
    @Test
    fun `a stale output row changes nothing`() = runTest {
        val outputs = FakeAutoOutputs.of(
            FakeAutoOutputs.output("bluetooth:buds", "Buds"),
            FakeAutoOutputs.output("speaker:phone", "Phone", DeviceKind.Speaker),
        )

        auto(outputs).children("${AutoLibrary.OUT_PREFIX}bluetooth:gone", now = null)

        assertEquals(listOf<String?>("bluetooth:gone"), outputs.chosen)
        assertNull(outputs.selected())
    }

    /**
     * ADR-0027's amendment — a declined request is visible in the car, not papered over.
     *
     * `setPreferredDevice` is a preference. A device run found the platform refusing the built-in speaker
     * while a headset was connected, and the app of the day reported success. The rows now carry two
     * different marks, so the driver can see that the sound is not where they sent it.
     */
    @Test
    fun `a declined choice is marked apart from where the sound actually is`() = runTest {
        val outputs = FakeAutoOutputs.of(
            FakeAutoOutputs.output("bluetooth:buds", "Buds"),
            FakeAutoOutputs.output("speaker:phone", "Phone speaker", DeviceKind.Speaker),
            selected = "speaker:phone",
        )
        // The platform kept the sound in the headset despite the speaker being asked for.
        outputs.routedId = "bluetooth:buds"

        val titles = auto(outputs).children(AutoLibrary.TAB_OUTPUT, now = null).titles()

        assertEquals(
            listOf("Automatic", "Buds — playing here", "Phone speaker — chosen, but not in use"),
            titles,
        )
    }

    /** When the platform does what it was asked, one row carries one mark and the rest are plain. */
    @Test
    fun `an honoured choice is marked once`() = runTest {
        val outputs = FakeAutoOutputs.of(
            FakeAutoOutputs.output("bluetooth:buds", "Buds"),
            FakeAutoOutputs.output("speaker:phone", "Phone speaker", DeviceKind.Speaker),
            selected = "bluetooth:buds",
        )

        val titles = auto(outputs).children(AutoLibrary.TAB_OUTPUT, now = null).titles()

        assertEquals(listOf("Automatic", "Buds — playing here", "Phone speaker"), titles)
    }

    /**
     * The confirmation after a tap names the *choice*, and does not claim the sound moved.
     *
     * It cannot know: the preference is applied on the player's thread after this returns, and the platform
     * may decline it. Saying "playing here" — which is what it used to say — would be a guess dressed as a
     * fact, on the one screen a driver reads without looking twice.
     */
    @Test
    fun `the confirmation names what was chosen rather than claiming a route`() = runTest {
        val outputs = FakeAutoOutputs.of(
            FakeAutoOutputs.output("bluetooth:buds", "Buds"),
            FakeAutoOutputs.output("speaker:phone", "Phone speaker", DeviceKind.Speaker),
        )

        val rows = auto(outputs).children("${AutoLibrary.OUT_PREFIX}speaker:phone", now = null)

        assertEquals(listOf("Chose Phone speaker"), rows.titles())
    }

    /** The id form is named in the diagnostic, so a log line can say what a driver tapped. */
    @Test
    fun `an output id has a kind of its own`() {
        assertEquals("out", AutoLibrary.kindOf("${AutoLibrary.OUT_PREFIX}bluetooth:buds"))
        assertEquals("tab", AutoLibrary.kindOf(AutoLibrary.TAB_OUTPUT))
    }

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

    private fun book(id: String, title: String, progress: MediaProgress? = null) = Book(
        serverId = SERVER,
        id = LibraryItemId(id),
        libraryId = LibraryId("lib-fiction"),
        title = title,
        subtitle = null,
        authors = listOf(Author(SERVER, AuthorId("author-1"), "Marisol Holt")),
        narrators = emptyList(),
        seriesMemberships = emptyList(),
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
        localAvailability = LocalAvailability.NotDownloaded,
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

        /**
         * PRODUCT_SPEC PLAY-003 — a no-op, because the car browse tree reads history and never
         * refreshes it. The pane that does is on the phone; a head unit showing a stale row is a
         * smaller problem than a car making a network call while somebody is driving.
         */
        override suspend fun refreshServerSessions(bookId: LibraryItemId) = Unit

        override suspend fun clear(bookId: LibraryItemId) = Unit
    }

    private companion object {
        val SERVER = ServerId("server-1")
        val PROFILE = ProfileId("profile-1")
    }
}
