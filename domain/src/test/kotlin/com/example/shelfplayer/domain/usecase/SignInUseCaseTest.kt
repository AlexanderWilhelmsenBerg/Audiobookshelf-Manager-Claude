package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerCapability
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.asFailure
import com.example.shelfplayer.domain.FakeAuthRepository
import com.example.shelfplayer.domain.FakeLibraryRepository
import com.example.shelfplayer.domain.TEST_PROFILE
import com.example.shelfplayer.domain.TEST_SERVER
import com.example.shelfplayer.domain.repository.CapabilityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC 6.1 — the first-launch sequence, and which parts of it are allowed to fail.
 *
 * The interesting assertions are the negative ones: a stored, valid session must survive a failed
 * handshake, because reporting it as a sign-in failure sends the user back to re-enter a password that was
 * correct — and discards a session that is stored and working.
 *
 * The initial sync is deliberately **not** here any more. It ran in the sign-in screen's scope, which a
 * successful sign-in cancels by popping the screen; home owns it now. `does not start the initial sync`
 * is the test that keeps it from drifting back.
 */
class SignInUseCaseTest {

    private val auth = FakeAuthRepository()
    private val capabilities = FakeCapabilityRepository()
    private val libraries = FakeLibraryRepository()

    private fun useCase() = SignInUseCase(auth, capabilities)

    private suspend fun signIn() = useCase()("https://books.example", "ada", "pw")

    @Test
    fun `a successful sign-in probes capabilities`() = runTest {
        val outcome = assertIs<AppResult.Success<SignInOutcome>>(signIn()).value

        assertEquals(TEST_PROFILE, outcome.profile.id)
        assertNull(outcome.warning)
        assertEquals(listOf(TEST_PROFILE), capabilities.handshakes)
    }

    /**
     * PRODUCT_SPEC LIB-001 — the first sync belongs to the screen that shows its result.
     *
     * Awaiting it here made the sign-in button spin for the length of an N+1 over every item in the
     * library, and then had that work cancelled when the screen was popped. Home starts it instead.
     */
    @Test
    fun `a successful sign-in does not start the initial sync`() = runTest {
        signIn()

        assertTrue(libraries.refreshedProfiles.isEmpty())
    }

    @Test
    fun `refused credentials store nothing and skip both later steps`() = runTest {
        auth.willFailToSignIn(AppError.Authentication())

        val result = useCase()("https://books.example", "ada", "wrong")

        assertIs<AppError.Authentication>(assertIs<AppResult.Failure>(result).error)
        assertTrue(capabilities.handshakes.isEmpty())
        assertTrue(libraries.refreshedProfiles.isEmpty())
    }

    /**
     * SYNC-001 already defines an unprobed server as "everything unsupported", so a failed handshake
     * degrades features with an explanation rather than undoing a valid sign-in.
     */
    @Test
    fun `a failed handshake still signs the user in and reports a warning`() = runTest {
        capabilities.result = AppError.Network().asFailure()

        val outcome = assertIs<AppResult.Success<SignInOutcome>>(signIn()).value

        assertEquals(TEST_PROFILE, outcome.profile.id)
        assertIs<AppError.Network>(assertNotNull(outcome.warning))
        // A server whose capabilities are unknown is still signed in and still browsable.
        assertEquals(false, outcome.profile.requiresReauthentication)
    }

    private class FakeCapabilityRepository : CapabilityRepository {

        /** Nothing in these tests downloads a file, so nothing observes a capability. */
        override suspend fun record(
            serverId: ServerId,
            capability: ServerCapability,
            isSupported: Boolean,
        ): AppResult<Unit> = AppResult.Success(Unit)
        var result: AppResult<ServerCapabilities> = AppResult.Success(ServerCapabilities.unknown(TEST_SERVER))
        val handshakes = mutableListOf<ProfileId>()

        override fun observeCapabilities(serverId: ServerId): Flow<ServerCapabilities?> = MutableStateFlow(null)

        override suspend fun capabilities(serverId: ServerId): ServerCapabilities? = null

        override suspend fun handshake(profileId: ProfileId): AppResult<ServerCapabilities> {
            handshakes += profileId
            return result
        }
    }
}
