package com.example.shelfplayer.feature.author

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.auth.AccountProgress
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.testing.MainDispatcherRule
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.ObserveAuthorUseCase
import com.example.shelfplayer.navigation.ShelfDestinations
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** LIB-002/AUTH-002: exercise the actual observer-to-ViewModel loading handoff, not a static screen. */
class AuthorViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun `pending first and switched profile queries show loading until availability is known`() = runTest {
        val fixture = com.example.shelfplayer.feature.home.profile()
        val profiles = MutableStateFlow<Profile?>(fixture)
        val books = MutableSharedFlow<List<Book>>()
        val profileRepository = profilesRepository(profiles, fixture)
        val libraryRepository = libraryRepository(books, fixture)
        val authorId = AuthorId("author")
        val viewModel = AuthorViewModel(
            SavedStateHandle(mapOf(ShelfDestinations.ARG_AUTHOR_ID to authorId.value)),
            ObserveAuthorUseCase(profileRepository, libraryRepository, main.testDispatcher),
        )
        viewModel.uiState.test {
            awaitItem()
            assertTrue(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.shelf)
            val book = com.example.shelfplayer.feature.home.book().copy(
                authors = listOf(Author(fixture.serverId, authorId, "Ada Ledger")),
            )
            books.emit(listOf(book))
            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.shelf != null)
            profiles.value = fixture.copy(id = ProfileId("incoming"))
            assertTrue(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.shelf)
            books.emit(emptyList())
            assertFalse(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.shelf)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun profilesRepository(profiles: MutableStateFlow<Profile?>, fixture: Profile): ProfileRepository =
        object : ProfileRepository {
            override fun observeProfiles() = flowOf(listOf(fixture))
            override fun observeServers(): Flow<List<Server>> = flowOf(emptyList())
            override fun observeActiveProfile() = profiles
            override suspend fun activeProfileId() = profiles.value?.id
            override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> = error("unused")
        }

    private fun libraryRepository(books: MutableSharedFlow<List<Book>>, fixture: Profile): LibraryRepository =
        object : LibraryRepository {
            override fun observeAccessibleBooks(profileId: ProfileId): Flow<List<Book>> = books
            override fun observeSyncState(profileId: ProfileId) = flowOf(SyncState.idle(fixture.serverId, profileId))
            override fun observeLibraries(profileId: ProfileId): Flow<List<Library>> = error("unused")
            override fun observeLibrary(profileId: ProfileId, libraryId: LibraryId): Flow<Library?> = error("unused")
            override fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>> = error("unused")
            override fun observeBook(profileId: ProfileId, bookId: LibraryItemId): Flow<Book?> = error("unused")
            override fun observeChapters(profileId: ProfileId, bookId: LibraryItemId): Flow<List<Chapter>> =
                error("unused")
            override suspend fun refresh(profileId: ProfileId): AppResult<Int> = error("unused")
            override suspend fun writeProgress(profileId: ProfileId, progress: List<AccountProgress>): AppResult<Int> =
                error("unused")
            override suspend fun searchServer(profileId: ProfileId, query: String): AppResult<Int> = error("unused")
        }
}
