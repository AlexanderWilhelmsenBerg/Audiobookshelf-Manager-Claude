package com.example.shelfplayer.feature.profiles

import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
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
import com.example.shelfplayer.core.model.auth.AccountBookmark
import com.example.shelfplayer.core.model.auth.AccountProgress
import com.example.shelfplayer.core.model.auth.AccountState
import com.example.shelfplayer.core.model.auth.LibraryAccess
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Bookmark
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.model.library.MediaProgress
import com.example.shelfplayer.core.model.lock.RelockDelay
import com.example.shelfplayer.core.model.lock.UnlockFailure
import com.example.shelfplayer.core.testing.MainDispatcherRule
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.domain.lock.ProfileActivationGuard
import com.example.shelfplayer.domain.playback.PlaybackHandover
import com.example.shelfplayer.domain.playback.StartupPlayer
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.BookmarkRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileLockRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.RememberedBookRepository
import com.example.shelfplayer.domain.sync.BackgroundSync
import com.example.shelfplayer.domain.usecase.RemoveProfileUseCase
import com.example.shelfplayer.domain.usecase.RestoreProfilePlaybackUseCase
import com.example.shelfplayer.domain.usecase.SwitchProfileUseCase
import com.example.shelfplayer.domain.usecase.SyncAccountUseCase
import com.example.shelfplayer.testing.FakePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** PRODUCT_SPEC AUTH-002 / 6.5 — the switcher's states and the actions it exposes. */
class ProfileSwitcherViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val profiles = FakeProfiles()
    private val auth = FakeAuth()
    private val libraries = StubLibraries()
    private val backgroundSync = RecordingBackgroundSync()
    private val preferences = FakePreferences()
    private val rememberedBooks = FakeRememberedBooks()
    private val locks = FakeLocks()

    /** PRODUCT_SPEC 6.5.6 — what the restore asked the player to do, if anything. */
    private val startupPlayer = RecordingStartupPlayer()

    private fun viewModel() = ProfileSwitcherViewModel(
        profiles,
        SwitchProfileUseCase(
            profiles,
            auth,
            SyncAccountUseCase(profiles, auth, libraries, StubBookmarks()),
            backgroundSync,
            ProfileActivationGuard { true },
            PlaybackHandover.None,
        ),
        RestoreProfilePlaybackUseCase(
            library = libraries,
            rememberedBooks = rememberedBooks,
            player = startupPlayer,
            logger = RedactingLogger(RecordingLogSink(), DefaultRedactor(RedactionPolicy.Default)),
        ),
        auth,
        RemoveProfileUseCase(auth, backgroundSync, preferences, rememberedBooks),
        locks,
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
        rememberedBooks.remember(ada.id, LibraryItemId("ada-book"))
        rememberedBooks.remember(grace.id, LibraryItemId("grace-book"))
        val viewModel = viewModel()
        val state = observed(viewModel)

        viewModel.onRemoveProfile(ada.id)

        assertEquals(listOf(ada.id), auth.removedProfiles)
        assertEquals(listOf("grace"), state.value.profiles.map { it.profile.displayName })
        assertNull(rememberedBooks.rememberedBook(ada.id), "removed profile must lose its remembered book")
        assertEquals(
            LibraryItemId("grace-book"),
            rememberedBooks.rememberedBook(grace.id),
            "removing one profile must not clear another profile's remembered book",
        )
    }

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
        assertEquals(grace.id, state.value.activeProfileId)
        auth.releaseRestore()
    }

    @Test
    fun `a passcode-protected profile is marked in the switcher`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)
        locks.setProtected(setOf(grace.id))

        val state = observed(viewModel())

        val rows = state.value.profiles.associateBy { it.profile.displayName }
        assertEquals(false, rows.getValue("ada").hasPasscode)
        assertEquals(true, rows.getValue("grace").hasPasscode, "grace has a passcode and the card must say so")
    }

    @Test
    fun `selecting a locked profile asks for its passcode instead of failing`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)
        locks.setProtected(setOf(grace.id))
        locks.setLocked(grace.id, passcode = "492817")
        val viewModel = viewModel()
        val state = observed(viewModel)

        viewModel.onProfileSelected(grace.id)

        assertEquals(grace.id, viewModel.unlockPrompt.value?.profileId, "the prompt names the locked profile")
        assertNull(state.value.error, "a prompt is not an error")
        assertEquals(ada.id, state.value.activeProfileId, "the switch must not happen before the passcode")
    }

    @Test
    fun `the right passcode unlocks and completes the switch`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)
        locks.setLocked(grace.id, passcode = "492817")
        val viewModel = viewModel()
        val state = observed(viewModel)
        viewModel.onProfileSelected(grace.id)

        viewModel.onUnlockSubmitted("492817".toCharArray())

        assertNull(viewModel.unlockPrompt.value, "the prompt closes on success")
        assertNull(state.value.error)
        assertEquals(grace.id, state.value.activeProfileId, "the switch the passcode was typed for happens")
    }

    @Test
    fun `a wrong passcode keeps the prompt open and does not switch`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)
        locks.setLocked(grace.id, passcode = "492817")
        val viewModel = viewModel()
        val state = observed(viewModel)
        viewModel.onProfileSelected(grace.id)

        viewModel.onUnlockSubmitted("111111".toCharArray())

        assertEquals(UnlockFailure.Wrong(remainingBeforeBackoff = 3), viewModel.unlockPrompt.value?.failure)
        assertEquals(ada.id, state.value.activeProfileId, "a wrong passcode switches nothing")
    }

    @Test
    fun `the submitted passcode is wiped`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)
        locks.setLocked(grace.id, passcode = "492817")
        val viewModel = viewModel()
        viewModel.onProfileSelected(grace.id)
        val typed = "492817".toCharArray()

        viewModel.onUnlockSubmitted(typed)

        assertEquals(CharArray(6).concatToString(), typed.concatToString(), "the array must not still hold digits")
    }

    @Test
    fun `an unlocked profile with a passcode is switched to without a prompt`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)
        locks.setProtected(setOf(grace.id))
        val viewModel = viewModel()
        val state = observed(viewModel)

        viewModel.onProfileSelected(grace.id)

        assertNull(viewModel.unlockPrompt.value, "an unlocked profile is not asked for a passcode")
        assertEquals(0, locks.submitted, "and nothing was submitted on its behalf")
        assertEquals(grace.id, state.value.activeProfileId)
    }

    @Test
    fun `dismissing the prompt changes nothing`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)
        locks.setLocked(grace.id, passcode = "492817")
        val viewModel = viewModel()
        val state = observed(viewModel)
        viewModel.onProfileSelected(grace.id)

        viewModel.onUnlockDismissed()

        assertNull(viewModel.unlockPrompt.value)
        assertEquals(ada.id, state.value.activeProfileId)
    }

    @Test
    fun `switching accounts restores the incoming profile's last book, paused`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)
        libraries.books = listOf(playedBook("half-finished"))
        rememberedBooks.remember(grace.id, LibraryItemId("half-finished"))

        viewModel().onProfileSelected(grace.id)

        assertEquals(listOf(LibraryItemId("half-finished")), startupPlayer.armed)
        assertTrue(startupPlayer.played.isEmpty(), "6.5.8 — a switch must never start audio")
        assertEquals(listOf(grace.id), libraries.accessibleBooksRequestedFor, "the incoming account's library")
    }

    @Test
    fun `a profile that needs reauthentication is not restored`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)
        libraries.books = listOf(playedBook("half-finished"))
        auth.restoreStatus = SessionStatus.ReauthenticationRequired

        viewModel().onProfileSelected(grace.id)

        assertTrue(startupPlayer.armed.isEmpty(), "no session, nothing to arm")
    }

    @Test
    fun `a refused switch restores nothing`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)
        libraries.books = listOf(playedBook("half-finished"))
        profiles.refuseSwitches()

        viewModel().onProfileSelected(grace.id)

        assertTrue(startupPlayer.armed.isEmpty())
    }

    private fun observed(viewModel: ProfileSwitcherViewModel): StateFlow<ProfileSwitcherUiState> {
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel.uiState
    }

    private val TestScope.backgroundScope get() = this.backgroundScope

    private val ada = profile("ada")
    private val grace = profile("grace")

    private fun profile(name: String) = Profile(
        id = ProfileId(name),
        server = booksServer,
        userId = "user-$name",
        displayName = name,
        role = ProfileRole.Listener,
        createdAt = Instant.EPOCH,
        lastUsedAt = Instant.EPOCH,
    )

    private class FakeProfiles : ProfileRepository {
        private val active = MutableStateFlow<Profile?>(null)
        private val all = MutableStateFlow<List<Profile>>(emptyList())
        private var acceptSwitches = true

        fun setProfiles(profiles: List<Profile>) {
            all.value = profiles
        }

        fun setActive(profileId: ProfileId) {
            active.value = all.value.firstOrNull { it.id == profileId }
        }

        fun remove(profileId: ProfileId) {
            all.value = all.value.filterNot { it.id == profileId }
            if (active.value?.id == profileId) active.value = all.value.firstOrNull()
        }

        fun refuseSwitches() {
            acceptSwitches = false
        }

        override fun observeActiveProfile(): Flow<Profile?> = active
        override fun observeProfiles(): Flow<List<Profile>> = all
        override suspend fun activeProfile(): Profile? = active.value
        override suspend fun profile(profileId: ProfileId): Profile? = all.value.firstOrNull { it.id == profileId }
        override suspend fun switchTo(profileId: ProfileId): AppResult<Unit> {
            if (!acceptSwitches) return AppError.InvalidInput("refused").asFailure()
            active.value = all.value.firstOrNull { it.id == profileId }
            return AppResult.Success(Unit)
        }
    }

    private class FakeAuth : AuthRepository {
        var signOutResult: AppResult<Unit> = AppResult.Success(Unit)
        val restoredProfiles = mutableListOf<ProfileId>()
        val signedOutProfiles = mutableListOf<ProfileId>()
        val removedProfiles = mutableListOf<ProfileId>()
        private var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var restoreStatus: SessionStatus = SessionStatus.Active

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
            return AppResult.Success(restoreStatus)
        }
        override suspend fun renewSession(profileId: ProfileId): AppResult<SessionStatus> = error("not part of this fake")
        override suspend fun requireReauthentication(profileId: ProfileId): AppResult<Unit> = error("not part of this fake")
        override suspend fun refreshPermissions(profileId: ProfileId): AppResult<AccountState> = AppResult.Success(
            AccountState(null, "test", ProfileRole.Listener, LibraryAccess.None),
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

    private fun playedBook(id: String) = Book(
        serverId = ServerId("srv_books"),
        id = LibraryItemId(id),
        libraryId = LibraryId("library"),
        title = "Book",
        subtitle = null,
        authors = emptyList(),
        seriesName = null,
        seriesSequence = null,
        description = null,
        duration = 60.minutes,
        coverUrl = null,
        publishedYear = null,
        narrator = null,
        genres = emptyList(),
        addedAt = null,
        lastFetchedAt = Instant.EPOCH,
        progress = MediaProgress(
            serverId = ServerId("srv_books"),
            profileId = grace.id,
            bookId = LibraryItemId(id),
            position = 10.minutes,
            duration = 60.minutes,
            isFinished = false,
            updatedAt = Instant.parse("2026-08-20T10:00:00Z"),
            hasUnsyncedChanges = false,
        ),
        localAvailability = LocalAvailability.NotDownloaded,
    )

    private class RecordingStartupPlayer : StartupPlayer {
        val armed = mutableListOf<LibraryItemId>()
        val played = mutableListOf<LibraryItemId>()
        override suspend fun arm(bookId: LibraryItemId) { armed += bookId }
        override suspend fun play(bookId: LibraryItemId) { played += bookId }
    }

    private class FakeRememberedBooks : RememberedBookRepository {
        private val values = mutableMapOf<ProfileId, LibraryItemId>()
        override suspend fun rememberedBook(profileId: ProfileId): LibraryItemId? = values[profileId]
        override suspend fun remember(profileId: ProfileId, bookId: LibraryItemId): AppResult<Unit> {
            values[profileId] = bookId
            return AppResult.Success(Unit)
        }
        override suspend fun forget(profileId: ProfileId): AppResult<Unit> {
            values.remove(profileId)
            return AppResult.Success(Unit)
        }
    }

    private class StubLibraries : LibraryRepository {
        val writtenFor = mutableListOf<ProfileId>()
        var books: List<Book> = emptyList()
        val accessibleBooksRequestedFor = mutableListOf<ProfileId>()
        override suspend fun writeProgress(profileId: ProfileId, progress: List<AccountProgress>): AppResult<Int> {
            writtenFor += profileId
            return AppResult.Success(progress.size)
        }
        override suspend fun searchServer(profileId: ProfileId, query: String): AppResult<Int> = AppResult.Success(0)
        override fun observeLibraries(profileId: ProfileId): Flow<List<Library>> = error("not part of this fake")
        override fun observeLibrary(profileId: ProfileId, libraryId: LibraryId): Flow<Library?> = error("not part of this fake")
        override fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>> = error("not part of this fake")
        override fun observeAccessibleBooks(profileId: ProfileId): Flow<List<Book>> {
            accessibleBooksRequestedFor += profileId
            return flowOf(books)
        }
        override fun observeChapters(profileId: ProfileId, bookId: LibraryItemId) = flowOf(emptyList<Chapter>())
        override fun observeBook(profileId: ProfileId, bookId: LibraryItemId): Flow<Book?> = error("not part of this fake")
        override fun observeSyncState(profileId: ProfileId): Flow<SyncState> = error("not part of this fake")
        override suspend fun refresh(profileId: ProfileId): AppResult<Int> = error("not part of this fake")
    }

    private class RecordingBackgroundSync : BackgroundSync {
        val cancelled = mutableListOf<ProfileId>()
        override suspend fun schedule(profileId: ProfileId) = Unit
        override suspend fun cancel(profileId: ProfileId) { cancelled += profileId }
    }
}

private val booksServer = Server(
    id = ServerId("srv_books"),
    displayName = "Books",
    baseUrl = "https://books.example",
    detectedVersion = "2.36.0",
    isFixture = false,
)

private class StubBookmarks : BookmarkRepository {
    override fun observe(bookId: LibraryItemId): Flow<List<Bookmark>> = flowOf(emptyList())
    override suspend fun add(bookId: LibraryItemId, at: Duration, title: String, owner: ProfileId?): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun rename(bookId: LibraryItemId, at: Duration, title: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun remove(bookId: LibraryItemId, at: Duration): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun writeAccountBookmarks(profileId: ProfileId, bookmarks: List<AccountBookmark>): AppResult<Int> = AppResult.Success(bookmarks.size)
}

private class FakeLocks : ProfileLockRepository {
    private val protectedProfiles = MutableStateFlow<Set<ProfileId>>(emptySet())
    private val locked = mutableSetOf<ProfileId>()
    private val accepts = mutableMapOf<ProfileId, String>()
    var submitted = 0
        private set
    fun setProtected(ids: Set<ProfileId>) { protectedProfiles.value = ids }
    fun setLocked(id: ProfileId, passcode: String) { locked += id; accepts[id] = passcode }
    override fun observeProtectedProfiles(): Flow<Set<ProfileId>> = protectedProfiles
    override suspend fun isLocked(profileId: ProfileId): Boolean = profileId in locked
    override suspend fun submitPasscode(profileId: ProfileId, passcode: CharArray): UnlockFailure? {
        submitted++
        if (String(passcode) != accepts[profileId]) return UnlockFailure.Wrong(remainingBeforeBackoff = 3)
        locked -= profileId
        return null
    }
    override fun observeLockState() = error("the switcher does not observe the lock state")
    override fun validate(passcode: CharArray) = error("not reached")
    override suspend fun hasPasscode(profileId: ProfileId) = error("not reached")
    override suspend fun preferences(profileId: ProfileId) = error("not reached")
    override suspend fun setPasscode(profileId: ProfileId, passcode: CharArray, current: CharArray?) = error("not reached")
    override suspend fun removePasscode(profileId: ProfileId, current: CharArray) = error("not reached")
    override suspend fun acceptBiometricUnlock(profileId: ProfileId) = error("not reached")
    override suspend fun setBiometricUnlockEnabled(profileId: ProfileId, enabled: Boolean) = error("not reached")
    override suspend fun setRelockDelay(profileId: ProfileId, delay: RelockDelay) = error("not reached")
    override suspend fun lockNow() = error("not reached")
    override suspend fun forget(profileId: ProfileId) = error("not reached")
}
