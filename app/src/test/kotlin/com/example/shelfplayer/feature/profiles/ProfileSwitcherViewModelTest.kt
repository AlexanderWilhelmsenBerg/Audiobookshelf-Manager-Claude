package com.example.shelfplayer.feature.profiles

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerCandidate
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.asFailure
import com.example.shelfplayer.core.model.auth.AccountProgress
import com.example.shelfplayer.core.model.auth.AccountState
import com.example.shelfplayer.core.model.auth.LibraryAccess
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.testing.MainDispatcherRule
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.sync.BackgroundSync
import com.example.shelfplayer.domain.usecase.RemoveProfileUseCase
import com.example.shelfplayer.domain.usecase.SwitchProfileUseCase
import com.example.shelfplayer.domain.usecase.SyncAccountUseCase
import com.example.shelfplayer.testing.FakePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PRODUCT_SPEC AUTH-002 / 6.5 — the switcher's states and the actions it exposes. */
class ProfileSwitcherViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val profiles = FakeProfiles()
    private val auth = FakeAuth()
    private val libraries = StubLibraries()
    private val backgroundSync = RecordingBackgroundSync()
    private val preferences = FakePreferences()

    private fun viewModel() = ProfileSwitcherViewModel(
        profiles,
        SwitchProfileUseCase(profiles, auth, SyncAccountUseCase(profiles, auth, libraries), backgroundSync),
        auth,
        RemoveProfileUseCase(auth, backgroundSync, preferences),
    )

    @Test
    fun `the switcher lists every saved profile and marks the active one`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)

        val state = observed(viewModel())

        assertEquals(listOf("ada", "grace"), state.value.profiles.map { it.profile.displayName })
        assertEquals(ada.id, state.value.activeProfileId)
    }

    @Test
    fun `selecting a profile switches to it`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)
        val viewModel = viewModel()
        val state = observed(viewModel)

        viewModel.onProfileSelected(grace.id)

        assertEquals(grace.id, state.value.activeProfileId)
        assertEquals(listOf(grace.id), auth.restoredProfiles)
    }

    /**
     * PRODUCT_SPEC AUTH-004 — signing out keeps the profile listed.
     *
     * The switcher is where a signed-out account has to remain visible: it still owns downloads and local
     * progress, and it is how the user gets back to it.
     */
    @Test
    fun `signing out keeps the profile in the list`() = runTest {
        profiles.setProfiles(listOf(ada))
        profiles.setActive(ada.id)
        val viewModel = viewModel()
        val state = observed(viewModel)

        viewModel.onSignOut(ada.id)

        assertEquals(listOf(ada.id), auth.signedOutProfiles)
        assertTrue(auth.removedProfiles.isEmpty(), "signing out must not remove the profile")
        assertEquals(listOf("ada"), state.value.profiles.map { it.profile.displayName })
    }

    @Test
    fun `removing a profile removes only that one`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)
        val viewModel = viewModel()
        val state = observed(viewModel)

        viewModel.onRemoveProfile(ada.id)

        assertEquals(listOf(ada.id), auth.removedProfiles)
        assertEquals(listOf("grace"), state.value.profiles.map { it.profile.displayName })
    }

    /** Removing the last profile is what sends the navigation graph back to onboarding. */
    @Test
    fun `removing the last profile reports that none remain`() = runTest {
        profiles.setProfiles(listOf(ada))
        profiles.setActive(ada.id)
        val viewModel = viewModel()
        val state = observed(viewModel)

        viewModel.onRemoveProfile(ada.id)

        assertTrue(state.value.hasNoProfiles)
    }

    @Test
    fun `a failed action surfaces its reason and can be dismissed`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)
        auth.signOutResult = AppError.Network().asFailure()
        val viewModel = viewModel()
        val state = observed(viewModel)

        viewModel.onSignOut(grace.id)
        assertIs<AppError.Network>(assertNotNull(state.value.error))

        viewModel.onErrorDismissed()

        assertNull(state.value.error)
    }

    /**
     * PRODUCT_SPEC 6.5 — the switch is atomic, so two of them must not overlap.
     *
     * Two in flight at once could leave the selection and the loaded credential describing different
     * profiles, which is exactly the cross-profile confusion the requirement rules out.
     */
    @Test
    fun `a second action is ignored while one is running`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)
        auth.holdRestore()
        val viewModel = viewModel()
        val state = observed(viewModel)

        viewModel.onProfileSelected(grace.id)
        viewModel.onProfileSelected(ada.id)

        assertEquals(1, auth.restoredProfiles.size)
        // The second selection never started, so the first is still the one in progress and the one the
        // selection reflects — the two cannot end up describing different profiles.
        assertEquals(grace.id, state.value.activeProfileId)
        auth.releaseRestore()
    }

    /**
     * Keeps the `WhileSubscribed` state flow hot and returns it.
     *
     * Without a collector the flow never leaves its initial value, and counting emissions instead is what
     * made the first version of these tests fragile: the number of intermediate states a combine produces
     * is an implementation detail, while the state the user ends up looking at is the requirement.
     */
    private fun TestScope.observed(viewModel: ProfileSwitcherViewModel): StateFlow<ProfileSwitcherUiState> =
        viewModel.uiState.also { flow ->
            backgroundScope.launch(mainDispatcherRule.testDispatcher) { flow.collect { } }
        }

    private val ada = profile("prf_ada", "ada")
    private val grace = profile("prf_grace", "grace")

    private fun profile(id: String, name: String) = Profile(
        id = ProfileId(id),
        serverId = ServerId("srv_books"),
        username = name,
        displayName = name,
        role = ProfileRole.Listener,
        requiresReauthentication = false,
        lastUsedAt = Instant.EPOCH,
        isFixture = false,
    )

    private class FakeProfiles : ProfileRepository {
        private val stored = MutableStateFlow<List<Profile>>(emptyList())
        private val active = MutableStateFlow<ProfileId?>(null)

        fun setProfiles(profiles: List<Profile>) {
            stored.value = profiles
        }

        fun setActive(profileId: ProfileId?) {
            active.value = profileId
        }

        override fun observeProfiles(): Flow<List<Profile>> = stored

        override fun observeServers(): Flow<List<Server>> = MutableStateFlow(listOf(booksServer))

        override fun observeActiveProfile(): Flow<Profile?> =
            active.map { id -> stored.value.firstOrNull { it.id == id } }

        override suspend fun activeProfileId(): ProfileId? = active.value

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> {
            active.value = profileId
            return AppResult.Success(Unit)
        }

        /** Removing a profile takes it out of the list, which is what the real repository's delete does. */
        fun remove(profileId: ProfileId) {
            stored.value = stored.value.filterNot { it.id == profileId }
            if (active.value == profileId) active.value = null
        }
    }

    private inner class FakeAuth : AuthRepository {
        var signOutResult: AppResult<Unit> = AppResult.Success(Unit)
        val restoredProfiles = mutableListOf<ProfileId>()
        val signedOutProfiles = mutableListOf<ProfileId>()
        val removedProfiles = mutableListOf<ProfileId>()

        private var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        fun holdRestore() {
            gate = kotlinx.coroutines.CompletableDeferred()
        }

        fun releaseRestore() {
            gate?.complete(Unit)
        }

        override suspend fun probeServer(serverUrl: String): AppResult<ServerCandidate> = error("not part of this fake")

        override suspend fun signIn(serverUrl: String, username: String, password: String): AppResult<Profile> =
            error("not part of this fake")

        override suspend fun restoreSession(profileId: ProfileId): AppResult<SessionStatus> {
            restoredProfiles += profileId
            gate?.await()
            return AppResult.Success(SessionStatus.Active)
        }

        override suspend fun renewSession(profileId: ProfileId): AppResult<SessionStatus> =
            error("not part of this fake")

        override suspend fun refreshPermissions(profileId: ProfileId): AppResult<AccountState> = AppResult.Success(
            AccountState(
                userId = null,
                username = "test",
                role = ProfileRole.Listener,
                access = LibraryAccess.None,
            ),
        )

        override suspend fun signOut(profileId: ProfileId): AppResult<Unit> {
            signedOutProfiles += profileId
            return signOutResult
        }

        override suspend fun removeProfile(profileId: ProfileId): AppResult<Unit> {
            removedProfiles += profileId
            profiles.remove(profileId)
            return AppResult.Success(Unit)
        }
    }

    /**
     * The switcher does not read the library; it only causes an account sync as a side effect of a
     * switch. Everything here fails loudly rather than returning empty, so a test that starts depending
     * on the library cannot pass by accident — [writeProgress] is the one call the switch really makes.
     */
    private class StubLibraries : LibraryRepository {
        val writtenFor = mutableListOf<ProfileId>()

        override suspend fun writeProgress(profileId: ProfileId, progress: List<AccountProgress>): AppResult<Int> {
            writtenFor += profileId
            return AppResult.Success(progress.size)
        }

        override fun observeLibraries(profileId: ProfileId): Flow<List<Library>> = error("not part of this fake")

        override fun observeLibrary(profileId: ProfileId, libraryId: LibraryId): Flow<Library?> =
            error("not part of this fake")

        override fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>> =
            error("not part of this fake")

        override fun observeAccessibleBooks(profileId: ProfileId): Flow<List<Book>> = error("not part of this fake")

        override fun observeBook(profileId: ProfileId, bookId: LibraryItemId): Flow<Book?> =
            error("not part of this fake")

        override fun observeSyncState(profileId: ProfileId): Flow<SyncState> = error("not part of this fake")

        override suspend fun refresh(profileId: ProfileId): AppResult<Int> = error("not part of this fake")
    }

    /** PRODUCT_SPEC SYNC-003 — "profile removal cancels its work". */
    private class RecordingBackgroundSync : BackgroundSync {
        val cancelled = mutableListOf<ProfileId>()

        override suspend fun schedule(profileId: ProfileId) = Unit

        override suspend fun cancel(profileId: ProfileId) {
            cancelled += profileId
        }
    }
}

/** The one server both test profiles live on — AUTH-002's "two accounts, one server" case. */
private val booksServer = Server(
    id = ServerId("srv_books"),
    displayName = "Books",
    baseUrl = "https://books.example",
    detectedVersion = "2.36.0",
    isFixture = false,
)
