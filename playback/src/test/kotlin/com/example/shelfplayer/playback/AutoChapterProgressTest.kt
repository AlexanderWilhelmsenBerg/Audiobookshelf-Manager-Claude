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
import com.example.shelfplayer.domain.lock.ProfileActivationGuard
import com.example.shelfplayer.domain.repository.LibraryRepository
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
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * PD-001 / ROUTE-001 — progress metadata that remains exposed after the old car Chapters destination retired.
 *
 * Chapter browsing belonged to the superseded Android Auto information architecture. These tests retain the
 * resumption and progress contracts that still reach a car without keeping that retired tree alive in tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class AutoChapterProgressTest {

    private val books = MutableStateFlow<List<Book>>(emptyList())

    @Test
    fun `the recent root offers the last played book at its stored position`() = runTest {
        givenBook(position = 80.minutes)

        val rows = auto().children(AutoLibrary.RECENT_ROOT, now = null)

        assertEquals(1, rows.size)
        assertEquals("at/book-1/${80.minutes.inWholeMilliseconds}", rows.single().mediaId)
        assertEquals(80.minutes, AutoLibrary.resolve(rows.single().mediaId)?.startAt)
    }

    @Test
    fun `resume metadata keeps the whole-book completion fraction`() = runTest {
        givenBook(position = 80.minutes)

        val item = auto().children(AutoLibrary.RECENT_ROOT, now = null).single()

        assertEquals(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED, item.completionStatus())
        assertEquals(80.0 / 240.0, item.completionPercentage()!!, TOLERANCE)
    }

    @Test
    fun `the recent root is empty when there is nothing to resume`() = runTest {
        books.value = listOf(book("book-1", "The Salt Harbour", progress = null))

        assertTrue(auto().children(AutoLibrary.RECENT_ROOT, now = null).isEmpty())
    }

    @Test
    fun `a finished book is not offered as a resume tile`() = runTest {
        books.value = listOf(book("book-1", "The Salt Harbour", progress(4.hours, isFinished = true)))

        assertTrue(auto().children(AutoLibrary.RECENT_ROOT, now = null).isEmpty())
    }

    @Test
    @Config(sdk = [34], qualifiers = "nb")
    fun `the four Android Auto destinations use the app language`() = runTest {
        val titles = auto().children(AutoLibrary.ROOT, now = null)
            .map { item -> item.mediaMetadata.title?.toString() }

        assertEquals(listOf("Fortsett", "Serier", "Forfattere", "Profiler"), titles)
    }

    @Test
    fun `the browse root is browsable unplayable and carries the app name`() = runTest {
        val root = auto().root()

        assertEquals(AutoLibrary.ROOT, root.mediaId)
        assertEquals(true, root.mediaMetadata.isBrowsable)
        assertEquals(false, root.mediaMetadata.isPlayable)
        assertEquals("BookWave", root.mediaMetadata.title)
    }

    private fun givenBook(position: Duration) {
        books.value = listOf(book("book-1", "The Salt Harbour", progress(position, isFinished = false)))
    }

    private fun MediaItem.completionStatus(): Int? =
        mediaMetadata.extras?.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS)

    private fun MediaItem.completionPercentage(): Double? = mediaMetadata.extras
        ?.takeIf { extras -> extras.containsKey(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE) }
        ?.getDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE)

    private fun auto(): AutoLibrary {
        val profiles = StubProfiles()
        val library = StubLibrary(books)
        return AutoLibrary(
            context = ApplicationProvider.getApplicationContext(),
            profiles = profiles,
            library = library,
            downloads = FakeAutoDownloads,
            rememberedBooks = FakeRememberedBooks(
                books.value.firstOrNull { book -> book.progress?.isFinished == false }?.id,
                PROFILE,
            ),
            homeShelves = ObserveHomeShelvesUseCase(profiles, library, UnconfinedTestDispatcher()),
            activation = ProfileActivationGuard { true },
            artwork = AutoArtwork.None,
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
        const val TOLERANCE = 0.001
    }
}
