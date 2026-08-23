package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.domain.FakeAuthRepository
import com.example.shelfplayer.domain.FakeBackgroundSync
import com.example.shelfplayer.domain.FakeBookmarkRepository
import com.example.shelfplayer.domain.FakeLibraryRepository
import com.example.shelfplayer.domain.FakeProfileRepository
import com.example.shelfplayer.domain.TEST_PROFILE
import com.example.shelfplayer.domain.lock.ProfileActivationGuard
import com.example.shelfplayer.domain.playback.PlaybackHandover
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** PRODUCT_SPEC 6.5 / AUTH-002 — switching accounts. */
class SwitchProfileUseCaseTest {

    private val profiles = JournallingProfiles()
    private val handover = RecordingHandover(profiles)
    private val auth = FakeAuthRepository()
    private val libraries = FakeLibraryRepository()
    private val backgroundSync = FakeBackgroundSync()

    /**
     * @param lockedProfiles profiles that may not be activated. A lambda rather than a fake class: the
     *   guard is a `fun interface` precisely so this test needs no double — see `ProfileActivationGuard`,
     *   which records why a duplicated fake was the thing to avoid.
     */
    private fun useCase(lockedProfiles: Set<ProfileId> = emptySet()) = SwitchProfileUseCase(
        profiles,
        auth,
        SyncAccountUseCase(profiles, auth, libraries, FakeBookmarkRepository()),
        backgroundSync,
        ProfileActivationGuard { profileId -> profileId !in lockedProfiles },
        handover,
    )

    @Test
    fun `switching selects the profile and loads its credential`() = runTest {
        val result = useCase()(OTHER)

        assertEquals(AppResult.Success(SessionStatus.Active), result)
        assertEquals(OTHER, profiles.activeProfileId())
        assertEquals(listOf(OTHER), auth.restoredProfiles)
    }

    /**
     * PRODUCT_SPEC 6.3 / AUTH-004 — a profile whose credential will not load still becomes active.
     *
     * Its cached library stays browsable and its downloads stay playable; the status is what tells the UI
     * to ask for a password when the network is next needed. Refusing the switch would leave the user
     * unable to reach an account they can see in the switcher.
     */
    @Test
    fun `a profile that needs reauthentication still becomes the active one`() = runTest {
        auth.willRestoreInto(SessionStatus.ReauthenticationRequired)

        val result = useCase()(OTHER)

        assertEquals(AppResult.Success(SessionStatus.ReauthenticationRequired), result)
        assertEquals(OTHER, profiles.activeProfileId())
    }

    /**
     * A profile that is not saved is refused before anything is written.
     *
     * PRODUCT_SPEC 6.5 requires the switch to be atomic, and the first half failing has to mean the second
     * half does not run — otherwise a credential is loaded for an account the app is not showing.
     */
    @Test
    fun `switching to an unsaved profile changes nothing`() = runTest {
        profiles.refuseSwitches()

        val result = useCase()(OTHER)

        assertIs<AppError.Validation>(assertIs<AppResult.Failure>(result).error)
        assertTrue(auth.restoredProfiles.isEmpty(), "no credential may be loaded for a refused switch")
    }

    /**
     * PRODUCT_SPEC 5.2 — the incoming account's grant is re-read before its content fills the screen.
     *
     * Without it, an account switched to keeps whatever grant it was signed in with, which is how a
     * library revoked days ago stays on its shelf.
     */
    @Test
    fun `switching re-reads the incoming profile's permissions`() = runTest {
        useCase()(OTHER)

        assertEquals(listOf(OTHER), auth.permissionRefreshes)
    }

    /**
     * A switch is not undone by an unreachable server.
     *
     * The user asked to be on the other account; PRODUCT_SPEC 6.3 keeps its cached library browsable
     * offline, so failing the switch would strand them on the one they were leaving.
     */
    @Test
    fun `a permission refresh that fails does not fail the switch`() = runTest {
        auth.willFailToRefreshPermissions(AppError.Network())

        val result = useCase()(OTHER)

        assertEquals(AppResult.Success(SessionStatus.Active), result)
        assertEquals(OTHER, profiles.activeProfileId())
    }

    /** A profile with no usable session has no token to ask with, so nothing is spent finding out. */
    @Test
    fun `a profile that needs reauthentication is not asked for permissions`() = runTest {
        auth.willRestoreInto(SessionStatus.ReauthenticationRequired)

        useCase()(OTHER)

        assertTrue(auth.permissionRefreshes.isEmpty())
    }

    // ---------------------------------------------------------------- PRODUCT_SPEC 6.5, the ordering

    /**
     * PRODUCT_SPEC 6.5 steps 2–4 — **the flush happens before the context changes, not alongside it.**
     *
     * This is the whole of the defect. `SwitchProfileUseCase` had no playback collaborator, so the
     * selection was written while the outgoing account's book kept playing and kept journaling a position
     * every five seconds against whichever profile `activeProfileId()` named — which, a microsecond later,
     * was the incoming one.
     *
     * The journal is asserted rather than a call count, because "it was called" is not the requirement.
     * 6.5 is an ordered list, and an implementation that flushed *after* `setActiveProfile` would satisfy
     * every count and none of the requirement.
     */
    @Test
    fun `playback is handed over before the active profile changes`() = runTest {
        useCase()(OTHER)

        assertEquals(listOf("handover:${TEST_PROFILE.value}", "active:${OTHER.value}"), profiles.journal)
    }

    /**
     * And the handover runs while the account being left is **still the active one**.
     *
     * The stronger half of the assertion above, and the one that names the failure: every write the flush
     * produces resolves the active profile if it was not given one, so the flush has to run in a moment
     * when that resolution is still correct. Recorded from inside the handover rather than around it.
     */
    @Test
    fun `the handover sees the outgoing profile as active`() = runTest {
        useCase()(OTHER)

        assertEquals(listOf(TEST_PROFILE), handover.handedOver)
        assertEquals(listOf<ProfileId?>(TEST_PROFILE), handover.activeWhenCalled)
    }

    /**
     * Re-selecting the account already in use does not stop the book.
     *
     * A switcher tap on the row that is already highlighted is a no-op, and product priority 1 is not to
     * interrupt playback. Tearing the player down and rebuilding it for a selection that changed nothing
     * would be an interruption with no switch behind it.
     */
    @Test
    fun `re-selecting the active profile leaves playback alone`() = runTest {
        useCase()(TEST_PROFILE)

        assertTrue(handover.handedOver.isEmpty(), "nothing was being left, so nothing is handed over")
    }

    /** AUTH-005 — a refused switch is refused before the player is touched, as it is before anything else. */
    @Test
    fun `a locked profile does not stop the outgoing account's playback`() = runTest {
        useCase(lockedProfiles = setOf(OTHER))(OTHER)

        assertTrue(handover.handedOver.isEmpty())
        assertTrue(profiles.journal.isEmpty())
    }

    /**
     * A switch that fails at step 4 has already stopped the player, and that is accepted rather than
     * overlooked.
     *
     * The only way to know whether `setActiveProfile` will succeed is to call it, and calling it first is
     * exactly the ordering 6.5 forbids. So a profile removed between the switcher listing it and the user
     * tapping it stops the book — a stray interruption in a race the switcher can barely produce, against
     * a cross-account write in one it produces every time.
     *
     * Nothing is *lost* when it happens: the handover's whole job is to write the position down first, and
     * it did. The listener presses play. This test exists so the trade is a decision somebody made rather
     * than a behaviour somebody finds.
     */
    @Test
    fun `a switch refused at the last step has still flushed the outgoing account`() = runTest {
        profiles.refuseSwitches()

        val result = useCase()(OTHER)

        assertIs<AppResult.Failure>(result)
        assertEquals(listOf(TEST_PROFILE), handover.handedOver)
    }

    private companion object {
        val OTHER = ProfileId("profile-2")
    }

    /**
     * A profile repository that writes down when its selection changed, so an ordering can be asserted.
     *
     * A decorator rather than a field on [FakeProfileRepository]: the journal only means anything next to
     * the handover's entries, and putting it in the shared fixture would leave every other test carrying a
     * list it never reads.
     */
    private class JournallingProfiles : ProfileRepository {
        private val delegate = FakeProfileRepository()

        /** What happened, in order. Shared with [RecordingHandover]. */
        val journal = mutableListOf<String>()

        override fun observeProfiles() = delegate.observeProfiles()

        override fun observeServers() = delegate.observeServers()

        override fun observeActiveProfile() = delegate.observeActiveProfile()

        override suspend fun activeProfileId(): ProfileId? = delegate.activeProfileId()

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> =
            delegate.setActiveProfile(profileId).also { result ->
                if (result is AppResult.Success) journal += "active:" + profileId.value
            }

        fun refuseSwitches() = delegate.refuseSwitches()
    }

    /**
     * A handover that records both what it was told and what it could see.
     *
     * [activeWhenCalled] is the assertion with teeth: the real implementation flushes a position, and a
     * position resolves the active profile when the caller has not named one. Recording what
     * `activeProfileId()` answered *inside* the handover is the only way to prove that resolution would
     * have been right.
     */
    private class RecordingHandover(private val profiles: JournallingProfiles) : PlaybackHandover {
        val handedOver = mutableListOf<ProfileId>()
        val activeWhenCalled = mutableListOf<ProfileId?>()

        override suspend fun handOver(outgoing: ProfileId) {
            handedOver += outgoing
            activeWhenCalled += profiles.activeProfileId()
            profiles.journal += "handover:" + outgoing.value
        }
    }

    /**
     * PRODUCT_SPEC SYNC-003 — an account the user actually uses earns a background schedule.
     *
     * On every switch rather than once, because the call is idempotent by unique work name and a
     * profile created before this existed would otherwise never get one.
     */
    @Test
    fun `switching schedules the background refresh for that profile`() = runTest {
        useCase()(OTHER)

        assertEquals(listOf(OTHER), backgroundSync.scheduled)
    }

    /** A profile with no usable session has nothing to sync, so nothing is scheduled for it. */
    @Test
    fun `a profile that needs reauthentication gets no schedule`() = runTest {
        auth.willRestoreInto(SessionStatus.ReauthenticationRequired)

        useCase()(OTHER)

        assertTrue(backgroundSync.scheduled.isEmpty())
    }

    /**
     * AUTH-005 — a locked profile is refused, and nothing is written.
     *
     * The assertion that matters is the second one. Refusing *after* writing the selection would leave the
     * app showing an account it then declined to open, and every screen reads its content from the active
     * profile — so the refusal has to come first or it is not a refusal at all.
     */
    @Test
    fun `switching to a locked profile is refused before anything is written`() = runTest {
        val before = profiles.activeProfileId()

        val result = useCase(lockedProfiles = setOf(OTHER))(OTHER)

        assertIs<AppResult.Failure>(result)
        assertIs<AppError.Security>(result.error)
        assertEquals(before, profiles.activeProfileId(), "the active profile must not have moved")
        assertEquals(emptyList(), auth.restoredProfiles, "no credential may be loaded for a refused switch")
    }

    /** With the guard allowing it — the passcode entered — the same profile switches normally. */
    @Test
    fun `a profile the guard allows switches normally`() = runTest {
        val result = useCase(lockedProfiles = emptySet())(OTHER)

        assertEquals(AppResult.Success(SessionStatus.Active), result)
        assertEquals(OTHER, profiles.activeProfileId())
    }
}
