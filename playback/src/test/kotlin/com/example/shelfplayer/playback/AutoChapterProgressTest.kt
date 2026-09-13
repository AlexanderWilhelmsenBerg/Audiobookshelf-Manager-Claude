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
 * recognise it as opting into Media3's Java annotation. Putting Media3's annotation on the class gives lint
 * the declaration-site marker it understands without leaking that API outside this test.
 */
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class AutoChapterProgressTest {

    private val books = MutableStateFlow<List<Book>>(emptyList())
    private val chapters = MutableStateFlow<List<Chapter>>(emptyList())

    @Test
    fun `a chapter before the book position is fully played`() = runTest {
        givenBook(position = 80.minutes)
        givenChapters(3)

        val rows = auto().children(AutoLibrary.TAB_CHAPTERS, now = null).drop(HEADER)

        assertEquals(
            listOf(FULLY_PLAYED, PARTIALLY_PLAYED, NOT_PLAYED),
            rows.map { it.completionStatus() },
        )
    }

    @Test
    fun `the current chapter reports progress relative to that chapter`() = runTest {
        givenBook(position = 80.minutes)
        givenChapters(3)

        val current = auto().children(AutoLibrary.TAB_CHAPTERS, now = null).drop(HEADER)[1]

        assertEquals(50.0, current.completionPercentage())
    }

    @Test
    fun `the book header reports full-book progress rather than current-chapter progress`() = runTest {
        givenBook(position = 80.minutes)
        givenChapters(3)

        val header = auto().children(AutoLibrary.TAB_CHAPTERS, now = null).first()

        assertEquals(80.minutes.inWholeMilliseconds.toDouble() / 4.hours.inWholeMilliseconds * 100.0, header.completionPercentage())
    }

    @Test
    fun `a finished book marks every chapter and the header fully played`() = runTest {
        givenBook(position = 4.hours, finished = true)
        givenChapters(3)

        val rows = auto().children(AutoLibrary.TAB_CHAPTERS, now = null)

        assertTrue(rows.all { it.completionStatus() == FULLY_PLAYED })
        assertTrue(rows.all { it.completionPercentage() == 100.0 })
    }

    @Test
    fun `a book without progress reports no completion badge`() = runTest {
        books.value = listOf(book("book-1", "Unstarted", progress = null))
        givenChapters(3)

        val rows = auto().children(AutoLibrary.TAB_CHAPTERS, now = null)

        assertTrue(rows.all { it.completionStatus() == null })
        assertTrue(rows.all { it.completionPercentage() == null })
    }

    private fun givenBook(position: Duration, finished: Boolean = false) {
        books.value = listOf(book("book-1", "The Salt Harbour", progress(position, finished)))
    }

    private fun givenChapters(count: Int) {
        chapters.value = (0 until count).map { index ->
            val start = index.hours
            chapter(index, start, start + 1.hours)
        }
    }

    private fun MediaItem.completionStatus(): Int? = mediaMetadata.extras
        ?.takeIf { it.containsKey(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS) }
        ?.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS)

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
            rememberedBooks = FakeRememberedBooks(),
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
        libraryId = LIBRARY,
        title = title,
        subtitle = null,
        authors = listOf(Author(AuthorId("author-1"), "Author")),
        seriesName = null,
        seriesSequence = null,
        description = null,
        duration = 4.hours,
        coverUrl = null,
        publishedYear = null,
        narrator = null,
        genres = emptyList(),
        addedAt = null,
        lastFetchedAt = Instant.EPOCH,
        progress = progress,
        localAvailability = LocalAvailability.NotDownloaded,
    )

    private class StubProfiles : ProfileRepository {
        override fun observeActiveProfile(): Flow<Profile?> = flowOf(profile)
        override fun observeProfiles(): Flow<List<Profile>> = flowOf(listOf(profile))
        override suspend fun activeProfile(): Profile? = profile
        override suspend fun profile(profileId: ProfileId): Profile? = profile.takeIf { it.id == profileId }
        override suspend fun switchTo(profileId: ProfileId): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class StubLibrary(
        private val books: Flow<List<Book>>,
        private val chapters: Flow<List<Chapter>>,
    ) : LibraryRepository {
        override fun observeLibraries(profileId: ProfileId): Flow<List<Library>> = emptyFlow()
        override fun observeLibrary(profileId: ProfileId, libraryId: LibraryId): Flow<Library?> = emptyFlow()
        override fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>> = books
        override fun observeAccessibleBooks(profileId: ProfileId): Flow<List<Book>> = books
        override fun observeChapters(profileId: ProfileId, bookId: LibraryItemId): Flow<List<Chapter>> = chapters
        override fun observeBook(profileId: ProfileId, bookId: LibraryItemId): Flow<Book?> = emptyFlow()
        override fun observeSyncState(profileId: ProfileId): Flow<SyncState> = emptyFlow()
        override suspend fun refresh(profileId: ProfileId): AppResult<Int> = AppResult.Success(0)
        override suspend fun searchServer(profileId: ProfileId, query: String): AppResult<Int> = AppResult.Success(0)
        override suspend fun writeProgress(profileId: ProfileId, progress: List<AccountProgress>): AppResult<Int> =
            AppResult.Success(progress.size)
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
        const val HEADER = 1
        const val NOT_PLAYED = MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
        const val PARTIALLY_PLAYED = MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
        const val FULLY_PLAYED = MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
        val PROFILE = ProfileId("profile-1")
        val SERVER = ServerId("server-1")
        val LIBRARY = LibraryId("library-1")
        val profile = Profile(
            id = PROFILE,
            server = Server(SERVER, "Server", "https://books.example", "2.0.0", isFixture = false),
            userId = "user-1",
            displayName = "Listener",
            role = ProfileRole.Listener,
            createdAt = Instant.EPOCH,
            lastUsedAt = Instant.EPOCH,
        )
    }
}
