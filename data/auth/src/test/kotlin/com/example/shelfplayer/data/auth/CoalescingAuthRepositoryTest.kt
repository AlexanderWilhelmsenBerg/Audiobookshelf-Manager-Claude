package com.example.shelfplayer.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.datastore.security.SessionTokenStore
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerCandidate
import com.example.shelfplayer.core.model.auth.AccountState
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.domain.repository.AuthRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** PRODUCT_SPEC AUTH-004 — every 401 caller shares one refresh-token generation boundary. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoalescingAuthRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val profileId = ProfileId("profile-a")

    @Test
    fun `session open and stream recovery rotate one refresh token once`() = runTest {
        tokenDirectory().deleteRecursively()
        val tokens = tokenProvider()
        tokens.adopt(profileId, FakeAuthGateway.session(), SERVER_URL)
        val refreshStarted = CompletableDeferred<Unit>()
        val allowRefresh = CompletableDeferred<Unit>()
        val delegate = CoordinatedFakeAuthRepository(tokens, refreshStarted, allowRefresh)
        val repository = CoalescingAuthRepository(delegate, tokens)

        // These two jobs model the independent callers that can meet the same expired access token:
        // opening a playback session and Media3 opening its next byte range.
        val sessionOpenRecovery = async(start = CoroutineStart.UNDISPATCHED) {
            repository.renewSession(profileId)
        }
        refreshStarted.await()
        val streamingRangeRecovery = async(start = CoroutineStart.UNDISPATCHED) {
            repository.renewSession(profileId)
        }

        assertEquals(1, delegate.renewCalls, "the second caller must wait at the shared boundary")
        allowRefresh.complete(Unit)

        assertEquals(AppResult.Success(SessionStatus.Active), sessionOpenRecovery.await())
        assertEquals(AppResult.Success(SessionStatus.Active), streamingRangeRecovery.await())
        assertEquals(1, delegate.renewCalls)
        assertEquals("access-2", tokens.accessTokenFor(profileId)?.value)
        assertEquals("refresh-2", tokens.refreshTokenFor(profileId)?.value)
        tokenDirectory().deleteRecursively()
    }

    @Test
    fun `sign out cannot be undone by an in flight renewal`() = runTest {
        tokenDirectory().deleteRecursively()
        val tokens = tokenProvider()
        tokens.adopt(profileId, FakeAuthGateway.session(), SERVER_URL)
        val refreshStarted = CompletableDeferred<Unit>()
        val allowRefresh = CompletableDeferred<Unit>()
        val delegate = CoordinatedFakeAuthRepository(tokens, refreshStarted, allowRefresh)
        val repository = CoalescingAuthRepository(delegate, tokens)

        val renewal = async(start = CoroutineStart.UNDISPATCHED) { repository.renewSession(profileId) }
        refreshStarted.await()
        val signOut = async(start = CoroutineStart.UNDISPATCHED) { repository.signOut(profileId) }
        assertEquals(0, delegate.signOutCalls, "sign-out must wait for the credential mutation in progress")

        allowRefresh.complete(Unit)
        renewal.await()
        signOut.await()

        assertEquals(1, delegate.signOutCalls)
        assertNull(tokens.accessTokenFor(profileId))
        assertNull(tokens.refreshTokenFor(profileId))
        assertNull(tokens.current())
        tokenDirectory().deleteRecursively()
    }

    private fun tokenProvider() = SessionTokenProvider(
        SessionTokenStore(
            context = context,
            cipher = ReversibleTestCipher(),
            ioDispatcher = UnconfinedTestDispatcher(),
        ),
    )

    private fun tokenDirectory() = File(context.filesDir, "sessions")

    private class CoordinatedFakeAuthRepository(
        private val tokens: SessionTokenProvider,
        private val refreshStarted: CompletableDeferred<Unit>,
        private val allowRefresh: CompletableDeferred<Unit>,
    ) : AuthRepository {
        var renewCalls = 0
            private set
        var signOutCalls = 0
            private set

        override suspend fun renewSession(profileId: ProfileId): AppResult<SessionStatus> {
            renewCalls += 1
            refreshStarted.complete(Unit)
            allowRefresh.await()
            tokens.adopt(
                profileId,
                FakeAuthGateway.session(accessToken = "access-2", refreshToken = "refresh-2"),
                SERVER_URL,
            )
            return AppResult.Success(SessionStatus.Active)
        }

        override suspend fun signOut(profileId: ProfileId): AppResult<Unit> {
            signOutCalls += 1
            tokens.clear(profileId)
            return AppResult.Success(Unit)
        }

        override suspend fun requireReauthentication(profileId: ProfileId): AppResult<Unit> {
            tokens.clear(profileId)
            return AppResult.Success(Unit)
        }

        override suspend fun probeServer(serverUrl: String): AppResult<ServerCandidate> = unused()

        override suspend fun signIn(serverUrl: String, username: String, password: String): AppResult<Profile> =
            unused()

        override suspend fun restoreSession(profileId: ProfileId): AppResult<SessionStatus> = unused()

        override suspend fun refreshPermissions(profileId: ProfileId): AppResult<AccountState> = unused()

        override suspend fun removeProfile(profileId: ProfileId): AppResult<Unit> = unused()

        private fun <T> unused(): AppResult<T> = error("not used by this test")
    }

    private companion object {
        const val SERVER_URL = "https://books.example"
    }
}
