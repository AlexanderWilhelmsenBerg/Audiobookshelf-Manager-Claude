package com.example.shelfplayer.playback

import androidx.core.net.toUri
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerCandidate
import com.example.shelfplayer.core.model.auth.AccountState
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.core.network.http.ActiveCredential
import com.example.shelfplayer.core.network.http.TokenProvider
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.usecase.RenewProfileSessionUseCase
import com.example.shelfplayer.domain.usecase.RequireProfileReauthenticationUseCase
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Same-origin credential recovery policy used by every Media3 transport surface. */
@RunWith(RobolectricTestRunner::class)
class PlaybackCredentialRenewerTest {
    private val profileId = ProfileId("profile-a")
    private val tokens = MutableTokenProvider(ActiveCredential("access-1", SERVER_URL, profileId))
    private val auth = FakeAuthRepository()
    private val renewer = PlaybackCredentialRenewer(
        tokens = tokens,
        renewSession = RenewProfileSessionUseCase(auth),
        requireReauthentication = RequireProfileReauthenticationUseCase(auth),
    )

    @Test
    fun `external media URL has no ABS token and cannot trigger renewal`() {
        val external = "https://cdn.example/audio.m4b".toUri()

        assertNull(renewer.tokenFor(external))
        assertFalse(renewer.recoverAfterUnauthorized(external, rejectedToken = null))
        assertEquals(0, auth.renewCalls)
    }

    @Test
    fun `same origin 401 renews once`() {
        val media = "https://books.example/audio.m4b".toUri()

        assertEquals("access-1", renewer.tokenFor(media))
        assertTrue(renewer.recoverAfterUnauthorized(media, rejectedToken = "access-1"))
        assertEquals(1, auth.renewCalls)
    }

    @Test
    fun `already changed access generation retries without rotating refresh token again`() {
        val media = "https://books.example/audio.m4b".toUri()
        tokens.credential = ActiveCredential("access-2", SERVER_URL, profileId)

        assertTrue(renewer.recoverAfterUnauthorized(media, rejectedToken = "access-1"))
        assertEquals(0, auth.renewCalls)
    }

    @Test
    fun `second same origin 401 marks reauthentication without renewing again`() {
        renewer.rejectRenewedCredential("https://books.example/audio.m4b".toUri())

        assertEquals(1, auth.requireReauthenticationCalls)
        assertEquals(0, auth.renewCalls)
    }

    private class MutableTokenProvider(var credential: ActiveCredential?) : TokenProvider {
        override fun current(): ActiveCredential? = credential
    }

    private class FakeAuthRepository : AuthRepository {
        var renewCalls = 0
            private set
        var requireReauthenticationCalls = 0
            private set

        override suspend fun renewSession(profileId: ProfileId): AppResult<SessionStatus> {
            renewCalls += 1
            return AppResult.Success(SessionStatus.Active)
        }

        override suspend fun requireReauthentication(profileId: ProfileId): AppResult<Unit> {
            requireReauthenticationCalls += 1
            return AppResult.Success(Unit)
        }

        override suspend fun probeServer(serverUrl: String): AppResult<ServerCandidate> = unused()
        override suspend fun signIn(serverUrl: String, username: String, password: String): AppResult<Profile> =
            unused()
        override suspend fun restoreSession(profileId: ProfileId): AppResult<SessionStatus> = unused()
        override suspend fun refreshPermissions(profileId: ProfileId): AppResult<AccountState> = unused()
        override suspend fun signOut(profileId: ProfileId): AppResult<Unit> = unused()
        override suspend fun removeProfile(profileId: ProfileId): AppResult<Unit> = unused()

        private fun <T> unused(): AppResult<T> = error("not used by this test")
    }

    private companion object {
        const val SERVER_URL = "https://books.example"
    }
}
