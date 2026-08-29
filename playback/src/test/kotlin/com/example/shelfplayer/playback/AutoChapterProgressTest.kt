package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
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
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.PlaybackHistoryEntry
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.ObserveHomeShelvesUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * PRODUCT_SPEC PLAY-003 / ROUTE-001 — **how far along am I**, asked from a car.
 *
 * ### Why this needs its own file
 *
 * `AutoBrowseTreeTest` is about which tabs exist. This is about what the rows inside one of them *say*, and
 * it is the half that cannot be checked by looking: an `EXTRAS_KEY_COMPLETION_STATUS` that is never set, or
 * set to the wrong constant, produces a browse screen that looks perfectly fine and is simply missing the
 * information the tab exists to carry. Nothing but an assertion catches that.
 *
 * ### The distinction the owner asked for
 *
 * *"chapters and how far along each chapter, compared to full progress"* — two different measurements that
 * a single percentage cannot express. A chapter bar is relative to its own chapter; the header row is
 * relative to the book. The tests below pin both, and pin that they disagree, because a build that computed
 * one and drew it in both places would pass any test that only checked one row.
 */
/*
 * Robolectric, for one reason: `Bundle`.
 *
 * The completion badge *is* a `Bundle`, and in a plain JVM unit test every Android class is a stub whose
 * methods return zero. `getInt` would answer `0` — which is `EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED` —
 * for a row that had never been written to at all, so a test asserting "not played" would pass against an
 * implementation that set no extras whatsoever. That is the exact defect this file exists to catch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
/*
 * `@UnstableApi` on the class rather than `@OptIn(UnstableApi::class)`, and the distinction is a lint one.
 *
 * `MediaConstants` — where the completion-badge keys live, and nowhere else — is Media3's unstable session
 * API. Kotlin's `@OptIn` accepts several markers at once, but Android Lint's `UnsafeOptInUsageError` does not
 * follow the multi-marker form into a companion object, so the constants at the bottom of this file were
 * still reported. Carrying the marker itself is the propagating form, which lint does understand.
 */
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class AutoChapterProgressTest {

    private val books = MutableStateFlow<List<Book>>(emptyList())
    private val chapters = MutableStateFlow<List<Chapter>>(emptyList())

    /**
     * The three states, in one list, from one position.
     *
     * Position is 1h20m. Chapter 1 (0–1h) is behind it, chapter 2 (1h–2h) contains it, chapter 3 (2h–3h) is
     * ahead. A car draws a full bar, a third-full bar and no bar.
     */
    @Test
    fun `each chapter says whether it is behind, around or ahead of the listener`() = runTest {
        givenBook(position = 80.minutes)
        givenChapters(3)

        val rows = auto().children(AutoLibrary.TAB_CHAPTERS, now = null).drop(HEADER)

        assertEquals(
            listOf(FULLY_PLAYED, PARTIALLY_PLAYED, NOT_PLAYED),
            rows.map { row -> row.completionStatus() },
        )
    }

    /**
     * The part-played chapter carries a fraction **of itself**, not of the book.
     *
     * Twenty minutes into a one-hour chapter is a third of the chapter and about a quarter of a four-hour
     * book. The row has to say a third. This is the assertion that fails if somebody wires the book's
     * `fractionComplete` into the chapter rows, which is the obvious wrong implementation and the one that
     * looks right on screen.
     */
    @Test
    fun `the current chapter is measured against itself and not against the book`() = runTest {
        givenBook(position = 80.minutes)
        givenChapters(4)

        val rows = auto().children(AutoLibrary.TAB_CHAPTERS, now = null)
        val header = rows.first()
        val current = rows.drop(HEADER)[1]

        // 20 minutes into a 60-minute chapter is a third of the chapter…
        assertEquals(1.0 / 3.0, current.completionPercentage()!!, TOLERANCE)
        // …and 80 minutes into a 4-hour book is a third of the book, which is a different third.
        assertEquals(80.0 / 240.0, header.completionPercentage()!!, TOLERANCE)
    }

    /** The header is the comparison the chapter bars need, and it is first so it is read first. */
    @Test
    fun `the header row states progress through the whole book`() = runTest {
        givenBook(position = 80.minutes)
        givenChapters(4)

        val header = auto().children(AutoLibrary.TAB_CHAPTERS, now = null).first()

        assertEquals("The Salt Harbour", header.mediaMetadata.title)
        assertEquals("33% of the book · 1:20:00 of 4:00:00 · chapter 2 of 4", header.mediaMetadata.subtitle)
    }

    /**
     * The live position wins over the stored one, for the book that is playing.
     *
     * Stored progress is 10 minutes and the player is at 80. A driver twenty minutes into a chapter whose
     * outbox has not synced must not be shown bars from before they started — that is a wrong answer, not a
     * stale one, and it is the case this whole `NowPlaying` argument exists for.
     */
    @Test
    fun `a playing book is drawn against the player rather than against stored progress`() = runTest {
        givenBook(position = 10.minutes)
        givenChapters(4)

        val rows = auto()
            .children(AutoLibrary.TAB_CHAPTERS, NowPlaying(LibraryItemId("book-1"), 80.minutes))
            .drop(HEADER)

        assertEquals(FULLY_PLAYED, rows[0].completionStatus())
        assertEquals(PARTIALLY_PLAYED, rows[1].completionStatus())
    }

    /**
     * PRODUCT_SPEC 5.2 — a book this profile cannot see has no chapters, and is not replaced by one it can.
     *
     * The tab is about *what is playing*. When the playing book is not in the grant-filtered list — the
     * profile lost the library, the car is holding a stale session — the honest answer is nothing. The
     * tempting fallback is the last-played book, and it would put one book's chapter list under another
     * book's now-playing screen, which is worse than an empty tab in a way a driver cannot diagnose.
     */
    @Test
    fun `a playing book outside the profile's grant shows no chapters at all`() = runTest {
        givenBook(position = 10.minutes)
        givenChapters(4)

        val rows = auto().children(AutoLibrary.TAB_CHAPTERS, NowPlaying(LibraryItemId("not-granted"), 3.hours))

        assertTrue(rows.isEmpty())
    }

    /** A mis-tagged file can give a chapter no length. It reads as unplayed rather than dividing by zero. */
    @Test
    fun `a zero-length chapter is unplayed rather than a division`() = runTest {
        givenBook(position = 80.minutes)
        chapters.value = listOf(chapter(0, Duration.ZERO, Duration.ZERO))

        val row = auto().children(AutoLibrary.TAB_CHAPTERS, now = null).drop(HEADER).single()

        assertEquals(NOT_PLAYED, row.completionStatus())
        assertNull(row.completionPercentage())
    }

    /**
     * PRODUCT_SPEC ROUTE-001 — the resume tile a car shows before anything has been tapped.
     *
     * One row, resuming at the stored position rather than at zero. The id is what carries that, and it is
     * the whole promise of the tile: `at/book-1/<millis>`.
     */
    @Test
    fun `the recent root offers the last played book at its stored position`() = runTest {
        givenBook(position = 80.minutes)

        val rows = auto().children(AutoLibrary.RECENT_ROOT, now = null)

        assertEquals(1, rows.size)
        assertEquals("at/book-1/${80.minutes.inWholeMilliseconds}", rows.single().mediaId)
        val target = AutoLibrary.resolve(rows.single().mediaId)
        assertEquals(80.minutes, target?.startAt)
    }

    /**
     * Nothing to resume means **no tile**, not an arbitrary book.
     *
     * A resume tile is one tap in a moving car. Offering a book nobody has started, because it was the only
     * thing to hand, turns "carry on where I was" into "start something at random".
     */
    @Test
    fun `the recent root is empty when there is nothing to resume`() = runTest {
        books.value = listOf(book("book-1", "The Salt Harbour", progress = null))

        assertTrue(auto().children(AutoLibrary.RECENT_ROOT, now = null).isEmpty())
    }

    /** A finished book is not something to carry on with, so it is not offered either. */
    @Test
    fun `a finished book is not offered as a resume tile`() = runTest {
        books.value = listOf(book("book-1", "The Salt Harbour", progress(4.hours, isFinished = true)))

        assertTrue(auto().children(AutoLibrary.RECENT_ROOT, now = null).isEmpty())
    }

    /**
     * PRODUCT_SPEC SET-002 / 2.10 — the car speaks the app's language, and says the app's name.
     *
     * Both halves of this failed silently for three phases. The tree was Kotlin literals, so the tabs were
     * English on a Norwegian phone, and the root said "ShelfPlayer" after the app became BookWave — a
     * literal is invisible to `MissingTranslation` and to anything else that compares the two `strings.xml`
     * files. Rendering the tree under a Norwegian configuration is the only check that catches either.
     */
    @Test
    @Config(sdk = [34], qualifiers = "nb")
    fun `the browse tree is drawn in the app's language`() = runTest {
        givenBook(position = 80.minutes)
        givenChapters(2)

        val header = auto().children(AutoLibrary.TAB_CHAPTERS, now = null).first()
        val subtitle = header.mediaMetadata.subtitle?.toString().orEmpty()

        // "kapittel 2 av 2", not "chapter 2 of 2".
        assertTrue(subtitle.contains("kapittel"), "expected Norwegian, got: $subtitle")
    }

    /**
     * The root: browsable, not playable, and carrying the app's *current* name.
     *
     * The name is the assertion the rename needed and did not have — the car said "ShelfPlayer" for three
     * phases. The browsable/playable pair moved here from `AutoLibraryTest` when the title became a
     * resource: that file is deliberately about the id protocol and has no `Context` to resolve one.
     */
    @Test
    fun `the browse root is browsable, unplayable and carries the app's name`() = runTest {
        val root = auto().root()

        assertEquals(AutoLibrary.ROOT, root.mediaId)
        assertEquals(true, root.mediaMetadata.isBrowsable)
        assertEquals(false, root.mediaMetadata.isPlayable)
        assertEquals("BookWave", root.mediaMetadata.title)
    }

    /** Every chapter row resumes the book *at that chapter*, which is what the tab is for. */
    @Test
    fun `a chapter row plays the book from that chapter`() = runTest {
        givenBook(position = 80.minutes)
        givenChapters(3)

        val rows = auto().children(AutoLibrary.TAB_CHAPTERS, now = null).drop(HEADER)
        val third = AutoLibrary.resolve(rows[2].mediaId)

        assertEquals(LibraryItemId("book-1"), third?.bookId)
        assertEquals(2.hours, third?.startAt)
    }

    // ---- fixtures -------------------------------------------------------------------------------------

    private fun givenBook(position: Duration) {
        books.value = listOf(book("book-1", "The Salt Harbour", progress(position, isFinished = false)))
    }

    /** [count] chapters of an hour each, so a position in minutes lands where the test says it does. */
    private fun givenChapters(count: Int) {
        chapters.value = (0 until count).map { index ->
            chapter(index, (index * MINUTES_PER_HOUR).minutes, ((index + 1) * MINUTES_PER_HOUR).minutes)
        }
    }

    private fun MediaItem.completionStatus(): Int? =
        mediaMetadata.extras?.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS)

    private fun MediaItem.completionPercentage(): Double? = mediaMetadata.extras
        ?.takeIf { it.containsKey(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE) }
        ?.getDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE)

    private fun chapter(index: Int, start: Duration, end: Duration) = Chapter(
        serverId = SERVER,
        bookId = LibraryItemId("book-1"),
        index = index,
        title = "Chapter ${index + 1}",
        start = start,
        end = end,
    )

    private fun auto(): AutoLibrary {
        val profiles = StubProfiles()
        val library = StubLibrary(books, chapters)
        return AutoLibrary(
            context = ApplicationProvider.getApplicationContext(),
            profiles = profiles,
            library = library,
            history = StubHistory(),
            homeShelves = ObserveHomeShelvesUseCase(profiles, library, UnconfinedTestDispatcher()),
            audioOutputs = FakeAutoOutputs(),
        )
    }

    private fun progress(position: Duration, isFinished: Boolean) = MediaProgress(
        serverId = SERVER,
        profileId = PROFILE,
        bookId = LibraryItemId("book-1"),
        position = position,
        duration = 4.hours,
        isFinished = isFinished,
        updatedAt = Instant.ofEpochMilli(1_000),
        hasUnsyncedChanges = false,
    )

    private fun book(id: String, title: String, progress: MediaProgress?) = Book(
        serverId = SERVER,
        id = LibraryItemId(id),
        libraryId = LibraryId("lib-fiction"),
        title = title,
        subtitle = null,
        authors = listOf(Author(SERVER, AuthorId("author-1"), "Marisol Holt")),
        narrators = emptyList(),
        seriesMemberships = emptyList(),
        duration = 4.hours,
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

        /** The book row the chapter rows are compared against. Dropped to assert on the chapters alone. */
        const val HEADER = 1
        const val MINUTES_PER_HOUR = 60

        /** Fractions are compared, not bit patterns: 20/60 is not exactly representable. */
        const val TOLERANCE = 0.001

        val FULLY_PLAYED = MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
        val PARTIALLY_PLAYED = MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
        val NOT_PLAYED = MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
    }
}
