package com.example.shelfplayer.feature.home

import app.cash.turbine.test
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibraryKind
import com.example.shelfplayer.core.testing.MainDispatcherRule
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.ObserveLibrariesUseCase
import com.example.shelfplayer.domain.usecase.RefreshLibraryUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** PRODUCT_SPEC LIB-001 / 21 — home distinguishes loading, empty, content and error. */
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val profiles = FakeProfiles()
    private val libraries = FakeLibraries()

    private fun viewModel() = HomeViewModel(
        observeLibraries = ObserveLibrariesUseCase(profiles, libraries),
        profileRepository = profiles,
        refreshLibrary = RefreshLibraryUseCase(profiles, libraries),
    )

    @Test
    fun `starts in the initial-load state before a profile exists`() = runTest {
        val state = viewModel().uiState.value

        assertEquals(true, state.isInitialLoad)
        assertEquals(emptyList(), state.libraries)
        assertNull(state.error)
    }

    @Test
    fun `shows cached libraries once they arrive`() = runTest {
        profiles.setActive(demoProfile)
        libraries.emit(listOf(demoLibrary))

        viewModel().uiState.test {
            val state = awaitItem()
            assertEquals(listOf("Fiction"), state.libraries.map { it.name })
            assertEquals(SyncStatus.Succeeded, state.syncStatus)
            assertFalse(state.isInitialLoad)
        }
    }

    @Test
    fun `a refresh failure surfaces the typed error without clearing content`() = runTest {
        profiles.setActive(demoProfile)
        libraries.emit(listOf(demoLibrary))
        libraries.refreshResult = AppResult.Failure(AppError.Network())

        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitItem()
            viewModel.refresh()
            val failed = awaitItem()
            assertEquals("network", failed.error?.code)
            assertEquals(SyncStatus.Failed, failed.syncStatus)
            // PRODUCT_SPEC LIB-001: cached content stays on screen.
            assertEquals(listOf("Fiction"), failed.libraries.map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismissing an error clears it`() = runTest {
        profiles.setActive(demoProfile)
        libraries.emit(listOf(demoLibrary))
        libraries.refreshResult = AppResult.Failure(AppError.Network())

        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitItem()
            viewModel.refresh()
            awaitItem()
            viewModel.dismissError()
            assertNull(awaitItem().error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** A double tap on refresh must not start two syncs (PRODUCT_SPEC MGR-004's debounce rule). */
    @Test
    fun `refresh is ignored while one is already running`() = runTest {
        profiles.setActive(demoProfile)
        val gate = CompletableDeferred<Unit>()
        libraries.gate = gate

        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitItem()
            viewModel.refresh()
            viewModel.refresh()
            assertEquals(1, libraries.refreshCount)
            gate.complete(Unit)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private val demoProfile = Profile(
        id = ProfileId("fixture-profile"),
        serverId = ServerId("fixture-server"),
        username = "demo",
        displayName = "Demo listener",
        role = ProfileRole.Listener,
        requiresReauthentication = false,
        lastUsedAt = Instant.EPOCH,
        isFixture = true,
    )

    private val demoLibrary = Library(
        serverId = ServerId("fixture-server"),
        id = LibraryId("lib-fiction"),
        name = "Fiction",
        kind = LibraryKind.Book,
        displayOrder = 0,
        bookCount = 5,
        remoteUpdatedAt = null,
        lastFetchedAt = Instant.EPOCH,
    )

    private class FakeProfiles : ProfileRepository {
        private val active = MutableStateFlow<Profile?>(null)

        fun setActive(profile: Profile?) {
            active.value = profile
        }

        override fun observeProfiles(): Flow<List<Profile>> = active.map(::listOfNotNull)

        override fun observeActiveProfile(): Flow<Profile?> = active

        override suspend fun activeProfileId(): ProfileId? = active.first()?.id

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class FakeLibraries : LibraryRepository {
        private val stored = MutableStateFlow<List<Library>>(emptyList())

        var refreshResult: AppResult<Int> = AppResult.Success(0)

        /** Lets a test hold a refresh open long enough to observe the in-flight guard. */
        var gate: CompletableDeferred<Unit>? = null
        var refreshCount: Int = 0
            private set

        fun emit(libraries: List<Library>) {
            stored.value = libraries
        }

        override fun observeLibraries(profileId: ProfileId): Flow<List<Library>> = stored

        override fun observeLibrary(profileId: ProfileId, libraryId: LibraryId): Flow<Library?> =
            stored.map { libraries -> libraries.firstOrNull { it.id == libraryId } }

        override fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>> =
            MutableStateFlow(emptyList())

        override fun observeBook(profileId: ProfileId, bookId: LibraryItemId): Flow<Book?> = MutableStateFlow(null)

        override fun observeSyncState(profileId: ProfileId): Flow<SyncState> =
            MutableStateFlow(SyncState.idle(ServerId("fixture-server"), profileId))

        override suspend fun refresh(profileId: ProfileId): AppResult<Int> {
            refreshCount++
            gate?.await()
            return refreshResult
        }
    }
}
