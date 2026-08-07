package com.example.shelfplayer.feature.settings

import app.cash.turbine.test
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.auth.AccountProgress
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibraryKind
import com.example.shelfplayer.core.testing.MainDispatcherRule
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.ObserveLibrariesUseCase
import com.example.shelfplayer.testing.FakePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PRODUCT_SPEC SET-001 / 6.1 step 9 — the one preference the settings screen holds. */
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val profiles = FakeProfiles()
    private val libraries = FakeLibraries()
    private val preferences = FakePreferences()

    private fun viewModel() =
        SettingsViewModel(observeLibraries = ObserveLibrariesUseCase(profiles, libraries), preferences = preferences)

    /**
     * PRODUCT_SPEC SET-002 — browsing by library lives here, as a list rather than a switch.
     *
     * The switch it replaced turned the home screen into something else, which meant going to Settings,
     * flipping it, and navigating back to find out what it did.
     */
    @Test
    fun `the libraries this profile may open are listed`() = runTest {
        libraries.emit(listOf(library("lib-fiction", "Fiction", 12), library("lib-nonfiction", "Non-fiction", 3)))

        viewModel().uiState.test {
            val state = awaitItem()
            assertEquals(listOf("Fiction", "Non-fiction"), state.libraries.map { it.name })
            assertEquals(listOf(12, 3), state.libraries.map { it.bookCount })
        }
    }

    /** PRODUCT_SPEC 6.1 step 9 — starring a library records it as the one the app opens on. */
    @Test
    fun `choosing a default library stores it`() = runTest {
        libraries.emit(listOf(library("lib-fiction", "Fiction", 12)))
        val model = viewModel()

        model.uiState.test {
            assertNull(awaitItem().defaultLibraryId, "no default until one is chosen")
            model.onDefaultLibraryChanged(LibraryId("lib-fiction"))
            assertEquals(LibraryId("lib-fiction"), awaitItem().defaultLibraryId)
        }
    }

    /** Clearing it returns the shelf to every granted library, which is not the same as choosing one. */
    @Test
    fun `clearing the default library removes the choice entirely`() = runTest {
        libraries.emit(listOf(library("lib-fiction", "Fiction", 12)))
        val model = viewModel()
        model.onDefaultLibraryChanged(LibraryId("lib-fiction"))

        model.uiState.test {
            assertEquals(LibraryId("lib-fiction"), awaitItem().defaultLibraryId)
            model.onDefaultLibraryChanged(null)
            assertNull(awaitItem().defaultLibraryId)
        }
    }

    /**
     * PRODUCT_SPEC 5.2 — a default library the profile has lost is not shown as a default.
     *
     * The stored id survives the grant being revoked, and rendering it raw would put a filled star
     * beside nothing at all — the library is no longer in the list to draw it against.
     */
    @Test
    fun `a default library the profile can no longer see is not reported as the default`() = runTest {
        libraries.emit(listOf(library("lib-fiction", "Fiction", 12)))
        val model = viewModel()
        model.onDefaultLibraryChanged(LibraryId("lib-fiction"))

        libraries.emit(listOf(library("lib-nonfiction", "Non-fiction", 3)))

        model.uiState.test {
            assertNull(awaitItem().defaultLibraryId)
        }
    }

    /** Zeroes before the first read would look like facts, so the screen says it is still reading. */
    @Test
    fun `the libraries are not reported until they have been read`() = runTest {
        assertFalse(SettingsUiState().isLoaded)

        viewModel().uiState.test {
            assertTrue(awaitItem().isLoaded)
        }
    }

    private fun library(id: String, name: String, bookCount: Int) = Library(
        serverId = ServerId("srv_books"),
        id = LibraryId(id),
        name = name,
        kind = LibraryKind.Book,
        displayOrder = 0,
        bookCount = bookCount,
        remoteUpdatedAt = null,
        lastFetchedAt = Instant.EPOCH,
    )

    private class FakeProfiles : ProfileRepository {
        private val active = MutableStateFlow<Profile?>(
            Profile(
                id = ProfileId("prf_ada"),
                serverId = ServerId("srv_books"),
                username = "ada",
                displayName = "ada",
                role = ProfileRole.Listener,
                requiresReauthentication = false,
                lastUsedAt = Instant.EPOCH,
                isFixture = false,
            ),
        )

        override fun observeProfiles(): Flow<List<Profile>> = MutableStateFlow(emptyList())

        override fun observeServers(): Flow<List<Server>> = MutableStateFlow(emptyList())

        override fun observeActiveProfile(): Flow<Profile?> = active

        override suspend fun activeProfileId(): ProfileId? = active.value?.id

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class FakeLibraries : LibraryRepository {
        private val stored = MutableStateFlow<List<Library>>(emptyList())

        fun emit(libraries: List<Library>) {
            stored.value = libraries
        }

        override fun observeLibraries(profileId: ProfileId): Flow<List<Library>> = stored

        override fun observeLibrary(profileId: ProfileId, libraryId: LibraryId): Flow<Library?> = MutableStateFlow(null)

        override fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>> =
            MutableStateFlow(emptyList())

        override fun observeAccessibleBooks(profileId: ProfileId): Flow<List<Book>> = MutableStateFlow(emptyList())

        override fun observeBook(profileId: ProfileId, bookId: LibraryItemId): Flow<Book?> = MutableStateFlow(null)

        override fun observeSyncState(profileId: ProfileId): Flow<SyncState> =
            MutableStateFlow(SyncState.idle(ServerId("srv_books"), profileId))

        override suspend fun refresh(profileId: ProfileId): AppResult<Int> = AppResult.Success(0)

        override suspend fun writeProgress(profileId: ProfileId, progress: List<AccountProgress>): AppResult<Int> =
            AppResult.Success(0)
    }
}
