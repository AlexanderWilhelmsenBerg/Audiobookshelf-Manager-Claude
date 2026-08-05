package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerCapabilities
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
 * handshake and a failed sync, because reporting either as a sign-in failure sends the user back to
 * re-enter a password that was correct — and discards a session that is stored and working.
 */
class SignInUseCaseTest {

    private val auth = FakeAuthRepository()
    private val capabilities = FakeCapabilityRepository()
    private val libraries = FakeLibraryRepository()

    private fun useCase() = SignInUseCase(auth, capabilities, libraries)

    private suspend fun signIn() = useCase()("https://books.example", "ada", "pw")

    @Test
    fun `a successful sign-in probes capabilities and syncs`() = runTest {
        val outcome = assertIs<AppResult.Success<SignInOutcome>>(signIn()).value

        assertEquals(TEST_PROFILE, outcome.profile.id)
        assertNull(outcome.warning)
        assertEquals(listOf(TEST_PROFILE), capabilities.handshakes)
        assertEquals(listOf(TEST_PROFILE), libraries.refreshedProfiles)
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
        // The sync still runs: a server whose capabilities are unknown can still be browsed.
        assertEquals(listOf(TEST_PROFILE), libraries.refreshedProfiles)
    }

    /** LIB-001 already handles an empty cached library as a visible, retryable sync state. */
    @Test
    fun `a failed first sync still signs the user in and reports a warning`() = runTest {
        libraries.refreshResult = AppError.Timeout().asFailure()

        val outcome = assertIs<AppResult.Success<SignInOutcome>>(signIn()).value

        assertEquals(TEST_PROFILE, outcome.profile.id)
        assertIs<AppError.Timeout>(assertNotNull(outcome.warning))
    }

    /**
     * The first failure is the one worth showing, and which one that is also pins the order down:
     * PRODUCT_SPEC SYNC-001 puts the handshake "on login", before the sync, so its error is the one that
     * surfaces when both fail.
     */
    @Test
    fun `the warning names the handshake when both steps fail`() = runTest {
        capabilities.result = AppError.Network().asFailure()
        libraries.refreshResult = AppError.Timeout().asFailure()

        val outcome = assertIs<AppResult.Success<SignInOutcome>>(signIn()).value

        assertIs<AppError.Network>(assertNotNull(outcome.warning))
    }

    private class FakeCapabilityRepository : CapabilityRepository {
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
