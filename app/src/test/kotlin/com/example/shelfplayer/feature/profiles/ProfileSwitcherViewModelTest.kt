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
            // AUTH-005 — always allows, which is right rather than lax. In production this guard and the
            // view model's `isLocked` are the same object, so by the time the switch is attempted the
            // passcode has already been accepted and the guard would allow it too. Refusing here would
            // test a state the app cannot be in.
            //
            // A lambda rather than a fake: `ProfileActivationGuard` is a `fun interface` so that this test
            // needs no double it would otherwise have to keep in step with `:domain`'s copy.
            ProfileActivationGuard { true },
            // PRODUCT_SPEC 6.5 — the switcher's own tests do not exercise the player, so the no-op the
            // interface documents is the right double here. `SwitchProfileUseCaseTest` is where the
            // ordering this seam exists to guarantee is actually asserted.
            PlaybackHandover.None,
        ),
        RestoreProfilePlaybackUseCase(
            library = libraries,
            player = startupPlayer,
            logger = RedactingLogger(RecordingLogSink(), DefaultRedactor(RedactionPolicy.Default)),
        ),
        auth,
        RemoveProfileUseCase(auth, backgroundSync, preferences),
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
    /**
     * AUTH-005 — a locked account says so before it is tapped.
     *
     * This exists because the lock made a silent switcher actively misleading: `SwitchProfileUseCase`
     * refuses a locked profile, so a card giving no sign of it offered an action the app would decline.
     */
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

    /**
     * **The dead end this closes.**
     *
     * The curtain draws for the *active* profile only — `observeLockState` reads `activeProfileId` — and
     * `SwitchProfileUseCase` refuses a locked profile *before* it becomes active. Those two rules met in a
     * dead end: tapping a locked card produced "That account is locked. Enter its passcode to switch to
     * it", and the app contained no field in which to enter it. A profile locked while a different account
     * was active could not be opened at all.
     *
     * The lock is asked before the switch, so the refusal is never reached and the switch never happens.
     */
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

    /** The whole point: the right passcode performs the switch that was refused. */
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

    /**
     * A wrong passcode keeps the prompt open with its reason, and does **not** switch.
     *
     * The failure is carried rather than dropped into the screen's general error line, because the two say
     * different things: one is "try again here", the other is "that action did not happen".
     */
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

    /**
     * The passcode is wiped by the view model, whatever the outcome.
     *
     * Asserted on the caller's own array, because that array is the copy the screen made from a `String`
     * it cannot wipe: leaving it populated would keep the digits reachable for as long as the composition
     * lived.
     */
    @Test
    fun `the submitted passcode is wiped`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)
        locks.setLocked(grace.id, passcode = "492817")
        val viewModel = viewModel()
        // No `observed` here: this test asserts on the array alone, and `unlockPrompt` is a plain
        // `StateFlow` that needs no subscriber to hold a value.
        viewModel.onProfileSelected(grace.id)
        val typed = "492817".toCharArray()

        viewModel.onUnlockSubmitted(typed)

        assertEquals(CharArray(6).concatToString(), typed.concatToString(), "the array must not still hold digits")
    }

    /** A profile carrying a passcode that is already unlocked must not be asked for it again. */
    @Test
    fun `an unlocked profile with a passcode is switched to without a prompt`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)
        // Protected, but holding a live ticket — which is what `isLocked` answers `false` for.
        locks.setProtected(setOf(grace.id))
        val viewModel = viewModel()
        val state = observed(viewModel)

        viewModel.onProfileSelected(grace.id)

        assertNull(viewModel.unlockPrompt.value, "an unlocked profile is not asked for a passcode")
        assertEquals(0, locks.submitted, "and nothing was submitted on its behalf")
        assertEquals(grace.id, state.value.activeProfileId)
    }

    /** Dismissing the prompt leaves the active profile alone rather than half-switching. */
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
        assertNull(state.value.error, "cancelling is not a failure")
    }

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

        private var refuse = false

        /** What the real repository does for a profile id that no longer resolves to a saved row. */
        fun refuseSwitches() {
            refuse = true
        }

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> {
            if (refuse) return AppResult.Failure(AppError.Validation(summary = "That profile is no longer saved."))
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

        /** PRODUCT_SPEC 6.5.6 — a profile with no usable credential must not be armed. */
        var restoreStatus: SessionStatus = SessionStatus.Active

        override suspend fun restoreSession(profileId: ProfileId): AppResult<SessionStatus> {
            restoredProfiles += profileId
            gate?.await()
            return AppResult.Success(restoreStatus)
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
    // ---------------------------------------------- PRODUCT_SPEC 6.5.6, the last step of the switch

    /**
     * **The wiring 6.5.6 asks for.** Switching accounts leaves the incoming one's book ready to play.
     *
     * `RestoreProfilePlaybackUseCaseTest` proves the selection rule; this proves something reaches it. That
     * split is deliberate: R-43 records that in this codebase the arithmetic is never what ships broken, and
     * a use case nobody calls is exactly how 6.5 step 6 stayed unbuilt after steps 2 to 5 landed.
     */
    @Test
    fun `switching accounts restores the incoming profile's last book, paused`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)
        libraries.books = listOf(playedBook("half-finished"))

        viewModel().onProfileSelected(grace.id)

        assertEquals(listOf(LibraryItemId("half-finished")), startupPlayer.armed)
        assertTrue(startupPlayer.played.isEmpty(), "6.5.8 — a switch must never start audio")
        assertEquals(listOf(grace.id), libraries.accessibleBooksRequestedFor, "the incoming account's library")
    }

    /**
     * A profile that could not open a session is not armed.
     *
     * Arming opens one, so a profile with no usable credential would spend a network round trip to be told
     * what `restoreSession` has already reported — the same reasoning `SwitchProfileUseCase` applies before
     * refreshing permissions.
     */
    @Test
    fun `a profile that needs reauthentication is not restored`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)
        libraries.books = listOf(playedBook("half-finished"))
        auth.restoreStatus = SessionStatus.ReauthenticationRequired

        viewModel().onProfileSelected(grace.id)

        assertTrue(startupPlayer.armed.isEmpty(), "no session, nothing to arm")
    }

    /** And a switch that failed outright restores nothing: there is no incoming account to restore. */
    @Test
    fun `a refused switch restores nothing`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)
        libraries.books = listOf(playedBook("half-finished"))
        profiles.refuseSwitches()

        viewModel().onProfileSelected(grace.id)

        assertTrue(startupPlayer.armed.isEmpty())
    }

    /**
     * The passcode path restores too, which is the call site most easily forgotten.
     *
     * There are two switches in this view model — a tap and an unlock — and a restore added to only the
     * first would work every time somebody tested it on an unlocked account.
     */
    @Test
    fun `unlocking a protected profile also restores its last book`() = runTest {
        profiles.setProfiles(listOf(ada, grace))
        profiles.setActive(ada.id)
        libraries.books = listOf(playedBook("half-finished"))
        locks.setLocked(grace.id, passcode = PASSCODE)
        val viewModel = viewModel()
        viewModel.onProfileSelected(grace.id)

        viewModel.onUnlockSubmitted(PASSCODE.toCharArray())

        assertEquals(listOf(LibraryItemId("half-finished")), startupPlayer.armed)
    }

    /** A book the incoming account is part-way through, which is the only kind 6.5.6 restores. */
    private fun playedBook(id: String) = Book(
        serverId = BOOKS_SERVER,
        id = LibraryItemId(id),
        libraryId = LibraryId("library-1"),
        title = id,
        subtitle = null,
        authors = emptyList(),
        narrators = emptyList(),
        seriesMemberships = emptyList(),
        duration = 60.minutes,
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
        addedAt = null,
        lastFetchedAt = Instant.EPOCH,
        progress = MediaProgress(
            serverId = BOOKS_SERVER,
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

    private companion object {
        val BOOKS_SERVER = ServerId("srv_books")

        /** The same passcode the other unlock tests in this file use. */
        const val PASSCODE = "492817"
    }

    private class RecordingStartupPlayer : StartupPlayer {
        val armed = mutableListOf<LibraryItemId>()
        val played = mutableListOf<LibraryItemId>()

        override suspend fun arm(bookId: LibraryItemId) {
            armed += bookId
        }

        override suspend fun play(bookId: LibraryItemId) {
            played += bookId
        }
    }

    private class StubLibraries : LibraryRepository {
        val writtenFor = mutableListOf<ProfileId>()

        /** PRODUCT_SPEC 6.5.6 — what the incoming account has, and which account was asked about. */
        var books: List<Book> = emptyList()
        val accessibleBooksRequestedFor = mutableListOf<ProfileId>()

        override suspend fun writeProgress(profileId: ProfileId, progress: List<AccountProgress>): AppResult<Int> {
            writtenFor += profileId
            return AppResult.Success(progress.size)
        }

        override suspend fun searchServer(profileId: ProfileId, query: String): AppResult<Int> = AppResult.Success(0)

        override fun observeLibraries(profileId: ProfileId): Flow<List<Library>> = error("not part of this fake")

        override fun observeLibrary(profileId: ProfileId, libraryId: LibraryId): Flow<Library?> =
            error("not part of this fake")

        override fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>> =
            error("not part of this fake")

        override fun observeAccessibleBooks(profileId: ProfileId): Flow<List<Book>> {
            accessibleBooksRequestedFor += profileId
            return flowOf(books)
        }

        override fun observeChapters(profileId: ProfileId, bookId: LibraryItemId) =

            kotlinx.coroutines.flow.flowOf(emptyList<com.example.shelfplayer.core.model.library.Chapter>())

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

/**
 * PRODUCT_SPEC 11.1 — bookmarks are written by the same account sync these tests drive.
 *
 * A stub rather than a fake: no test in this file asserts anything about bookmarks, and one that recorded
 * them would invite a reader to think it did.
 */
private class StubBookmarks : BookmarkRepository {
    override fun observe(bookId: LibraryItemId): Flow<List<Bookmark>> = flowOf(emptyList())

    override suspend fun add(bookId: LibraryItemId, at: Duration, title: String): AppResult<Unit> =
        AppResult.Success(Unit)

    override suspend fun rename(bookId: LibraryItemId, at: Duration, title: String): AppResult<Unit> =
        AppResult.Success(Unit)

    override suspend fun remove(bookId: LibraryItemId, at: Duration): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun writeAccountBookmarks(
        profileId: ProfileId,
        bookmarks: List<AccountBookmark>,
    ): AppResult<Int> = AppResult.Success(bookmarks.size)
}

/**
 * AUTH-005 — a lock repository that answers only the question the switcher asks.
 *
 * Everything else throws rather than returning a plausible default. R-37 is why: a fake that silently
 * answers a question its subject was not supposed to ask stops testing what it claims to, and in this
 * codebase that has already hidden a defect which emptied libraries.
 */
private class FakeLocks : ProfileLockRepository {
    private val protectedProfiles = MutableStateFlow<Set<ProfileId>>(emptySet())

    /** Which profiles answer `true` to [isLocked] — carrying a passcode is not the same as being locked. */
    private val locked = mutableSetOf<ProfileId>()

    /** Passcodes that [submitPasscode] accepts, by profile. Anything else is [UnlockFailure.Wrong]. */
    private val accepts = mutableMapOf<ProfileId, String>()

    var submitted = 0
        private set

    fun setProtected(ids: Set<ProfileId>) {
        protectedProfiles.value = ids
    }

    fun setLocked(id: ProfileId, passcode: String) {
        locked += id
        accepts[id] = passcode
    }

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
    override suspend fun setPasscode(profileId: ProfileId, passcode: CharArray, current: CharArray?) =
        error("not reached")

    override suspend fun removePasscode(profileId: ProfileId, current: CharArray) = error("not reached")
    override suspend fun acceptBiometricUnlock(profileId: ProfileId) = error("not reached")
    override suspend fun setBiometricUnlockEnabled(profileId: ProfileId, enabled: Boolean) = error("not reached")

    override suspend fun setRelockDelay(profileId: ProfileId, delay: RelockDelay) = error("not reached")
    override suspend fun lockNow() = error("not reached")
    override suspend fun forget(profileId: ProfileId) = error("not reached")
}
