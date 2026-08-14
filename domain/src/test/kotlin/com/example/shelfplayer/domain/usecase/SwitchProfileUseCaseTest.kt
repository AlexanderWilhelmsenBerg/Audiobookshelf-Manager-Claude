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
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** PRODUCT_SPEC 6.5 / AUTH-002 — switching accounts. */
class SwitchProfileUseCaseTest {

    private val profiles = FakeProfileRepository()
    private val auth = FakeAuthRepository()
    private val libraries = FakeLibraryRepository()
    private val backgroundSync = FakeBackgroundSync()

    private fun useCase() = SwitchProfileUseCase(
        profiles,
        auth,
        SyncAccountUseCase(profiles, auth, libraries, FakeBookmarkRepository()),
        backgroundSync,
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

    private companion object {
        val OTHER = ProfileId("profile-2")
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
}
