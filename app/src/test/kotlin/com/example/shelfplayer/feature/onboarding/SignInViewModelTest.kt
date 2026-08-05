package com.example.shelfplayer.feature.onboarding

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.ServerCandidate
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.ServerProbe
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.asFailure
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.testing.MainDispatcherRule
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.CapabilityRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.usecase.SignInUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC AUTH-001 / 6.1 / 21 — the sign-in screen's states and the order it enforces.
 *
 * The assertions worth reading are the ones about what the screen refuses to do: reach the password field
 * before the address is confirmed, keep a password after submitting it, and navigate twice.
 */
class SignInViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val auth = RecordingAuthRepository()
    private val capabilities = StubCapabilityRepository()
    private val libraries = StubLibraryRepository()

    private fun viewModel() = SignInViewModel(auth, SignInUseCase(auth, capabilities, libraries))

    // --- PRODUCT_SPEC 6.1 steps 3-4: the address, before the password -----------------------------

    @Test
    fun `the password field is not reachable until the address is confirmed`() = runTest {
        val viewModel = viewModel()

        assertEquals(SignInStage.Address, viewModel.uiState.value.stage)
        viewModel.onServerUrlChanged("books.example")
        assertEquals(SignInStage.Address, viewModel.uiState.value.stage)

        viewModel.onServerSubmitted()

        assertEquals(SignInStage.Credentials, viewModel.uiState.value.stage)
    }

    /** The user should see the address that will actually be contacted, not the one they typed. */
    @Test
    fun `the confirmed address replaces what was typed`() = runTest {
        val viewModel = viewModel()
        viewModel.onServerUrlChanged("books.example/")

        viewModel.onServerSubmitted()

        assertEquals("https://books.example", viewModel.uiState.value.serverUrl)
        assertEquals(true, viewModel.uiState.value.candidate?.wasSchemeAssumed)
    }

    @Test
    fun `a rejected address keeps the user on the address field with the reason`() = runTest {
        auth.probeResult = AppError.ApiCompatibility(
            summary = "That address answered, but it is not an Audiobookshelf server.",
            missingField = "app",
        ).asFailure()
        val viewModel = viewModel()
        viewModel.onServerUrlChanged("https://not-abs.example")

        viewModel.onServerSubmitted()

        assertEquals(SignInStage.Address, viewModel.uiState.value.stage)
        assertIs<AppError.ApiCompatibility>(assertNotNull(viewModel.uiState.value.error))
        assertTrue(auth.signInCalls.isEmpty(), "no credential may be sent to a rejected address")
    }

    @Test
    fun `editing the address clears the previous reason`() = runTest {
        auth.probeResult = AppError.Network().asFailure()
        val viewModel = viewModel()
        viewModel.onServerUrlChanged("https://down.example")
        viewModel.onServerSubmitted()
        assertNotNull(viewModel.uiState.value.error)

        viewModel.onServerUrlChanged("https://up.example")

        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `an empty address cannot be submitted`() = runTest {
        assertFalse(viewModel().uiState.value.canSubmitServer)
    }

    // --- PRODUCT_SPEC AUTH-001: the credentials ---------------------------------------------------

    @Test
    fun `signing in sends the confirmed address, not the typed one`() = runTest {
        val viewModel = credentialsStage()
        viewModel.onUsernameChanged("ada")
        viewModel.onPasswordChanged("pw")

        viewModel.onCredentialsSubmitted()

        assertEquals(listOf(SignIn("https://books.example", "ada", "pw")), auth.signInCalls)
    }

    /** PRODUCT_SPEC AUTH-003 — the password is used and dropped, on both paths. */
    @Test
    fun `the password is discarded whether sign-in succeeds or fails`() = runTest {
        val succeeding = credentialsStage()
        succeeding.onUsernameChanged("ada")
        succeeding.onPasswordChanged("pw")
        succeeding.onCredentialsSubmitted()
        assertEquals("", succeeding.uiState.value.password)

        auth.signInResult = AppError.Authentication().asFailure()
        val failing = credentialsStage()
        failing.onUsernameChanged("ada")
        failing.onPasswordChanged("wrong")
        failing.onCredentialsSubmitted()
        assertEquals("", failing.uiState.value.password)
        // The username survives: the likely mistake is the password, and retyping both is a needless
        // annoyance.
        assertEquals("ada", failing.uiState.value.username)
    }

    @Test
    fun `a wrong password keeps the user on the credentials stage`() = runTest {
        auth.signInResult = AppError.Authentication().asFailure()
        val viewModel = credentialsStage()
        viewModel.onUsernameChanged("ada")
        viewModel.onPasswordChanged("wrong")

        viewModel.onCredentialsSubmitted()

        assertEquals(SignInStage.Credentials, viewModel.uiState.value.stage)
        assertIs<AppError.Authentication>(assertNotNull(viewModel.uiState.value.error))
        assertNull(viewModel.uiState.value.signedIn)
    }

    /**
     * PRODUCT_SPEC AUTH-001 — "a clear certificate error".
     *
     * The screen shows `AppError.summary` and `code`, so a TLS failure reads differently from a wrong
     * password without the screen having to classify anything itself.
     */
    @Test
    fun `a certificate failure is reported as a security error, not a wrong password`() = runTest {
        auth.signInResult = AppError.Security(
            summary = "The server's security certificate could not be verified.",
        ).asFailure()
        val viewModel = credentialsStage()
        viewModel.onUsernameChanged("ada")
        viewModel.onPasswordChanged("pw")

        viewModel.onCredentialsSubmitted()

        val error = assertNotNull(viewModel.uiState.value.error)
        assertIs<AppError.Security>(error)
        assertEquals("security", error.code)
    }

    @Test
    fun `a blank username or password cannot be submitted`() = runTest {
        val viewModel = credentialsStage()

        assertFalse(viewModel.uiState.value.canSubmitCredentials)
        viewModel.onUsernameChanged("ada")
        assertFalse(viewModel.uiState.value.canSubmitCredentials)
        viewModel.onPasswordChanged("pw")
        assertTrue(viewModel.uiState.value.canSubmitCredentials)
    }

    /** A signed-in signal is consumed once, so a recomposition cannot navigate a second time. */
    @Test
    fun `the signed-in signal is cleared once handled`() = runTest {
        val viewModel = credentialsStage()
        viewModel.onUsernameChanged("ada")
        viewModel.onPasswordChanged("pw")

        viewModel.onCredentialsSubmitted()
        assertNotNull(viewModel.uiState.value.signedIn)

        viewModel.onSignedInHandled()

        assertNull(viewModel.uiState.value.signedIn)
    }

    /**
     * PRODUCT_SPEC 6.1 — a failed handshake or first sync is a caveat, not a failed sign-in.
     *
     * The profile exists and its session is stored, so the screen must navigate onward carrying the
     * warning rather than sending the user back to a password field.
     */
    @Test
    fun `a warning after a successful sign-in still signs the user in`() = runTest {
        libraries.refreshResult = AppError.Timeout().asFailure()
        val viewModel = credentialsStage()
        viewModel.onUsernameChanged("ada")
        viewModel.onPasswordChanged("pw")

        viewModel.onCredentialsSubmitted()

        val signedIn = assertNotNull(viewModel.uiState.value.signedIn)
        assertIs<AppError.Timeout>(assertNotNull(signedIn.warning))
        assertNull(viewModel.uiState.value.error, "a caveat is not an error on this screen")
    }

    /** Going back to the address discards the probe result and the password with it. */
    @Test
    fun `changing server resets the credentials stage`() = runTest {
        val viewModel = credentialsStage()
        viewModel.onUsernameChanged("ada")
        viewModel.onPasswordChanged("pw")

        viewModel.onBackToServer()

        val state = viewModel.uiState.value
        assertEquals(SignInStage.Address, state.stage)
        assertEquals("", state.password)
        assertEquals("", state.username)
        assertNull(state.candidate)
        assertEquals("https://books.example", state.serverUrl)
    }

    private fun credentialsStage(): SignInViewModel = viewModel().apply {
        onServerUrlChanged("books.example")
        onServerSubmitted()
    }

    private data class SignIn(val serverUrl: String, val username: String, val password: String)

    private class RecordingAuthRepository : AuthRepository {
        var probeResult: AppResult<ServerCandidate> = AppResult.Success(
            ServerCandidate(
                serverUrl = "https://books.example",
                isCleartext = false,
                wasSchemeAssumed = true,
                probe = ServerProbe(
                    isAudiobookshelf = true,
                    serverVersion = "2.36.0",
                    isInitialized = true,
                    authMethods = listOf("local"),
                ),
            ),
        )
        var signInResult: AppResult<Profile> = AppResult.Success(
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
        val signInCalls = mutableListOf<SignIn>()

        override suspend fun probeServer(serverUrl: String): AppResult<ServerCandidate> = probeResult

        override suspend fun signIn(serverUrl: String, username: String, password: String): AppResult<Profile> {
            signInCalls += SignIn(serverUrl, username, password)
            return signInResult
        }

        override suspend fun restoreSession(profileId: ProfileId): AppResult<SessionStatus> =
            AppResult.Success(SessionStatus.Active)

        override suspend fun renewSession(profileId: ProfileId): AppResult<SessionStatus> =
            AppResult.Success(SessionStatus.Active)

        override suspend fun signOut(profileId: ProfileId): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun removeProfile(profileId: ProfileId): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class StubCapabilityRepository : CapabilityRepository {
        var result: AppResult<ServerCapabilities> =
            AppResult.Success(ServerCapabilities.unknown(ServerId("srv_books")))

        override fun observeCapabilities(serverId: ServerId): Flow<ServerCapabilities?> = MutableStateFlow(null)

        override suspend fun capabilities(serverId: ServerId): ServerCapabilities? = null

        override suspend fun handshake(profileId: ProfileId): AppResult<ServerCapabilities> = result
    }

    private class StubLibraryRepository : LibraryRepository {
        var refreshResult: AppResult<Int> = AppResult.Success(0)

        override fun observeLibraries(profileId: ProfileId): Flow<List<Library>> = MutableStateFlow(emptyList())

        override fun observeLibrary(profileId: ProfileId, libraryId: LibraryId): Flow<Library?> = MutableStateFlow(null)

        override fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>> =
            MutableStateFlow(emptyList())

        override fun observeBook(profileId: ProfileId, bookId: LibraryItemId): Flow<Book?> = MutableStateFlow(null)

        override fun observeSyncState(profileId: ProfileId): Flow<SyncState> =
            MutableStateFlow(SyncState.idle(ServerId("srv_books"), profileId))

        override suspend fun refresh(profileId: ProfileId): AppResult<Int> = refreshResult
    }
}
