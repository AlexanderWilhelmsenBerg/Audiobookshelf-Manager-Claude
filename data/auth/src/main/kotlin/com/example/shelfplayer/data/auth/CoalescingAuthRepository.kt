package com.example.shelfplayer.data.auth

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerCandidate
import com.example.shelfplayer.core.model.auth.AccountState
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.domain.repository.AuthRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes refresh-token rotation for every caller of [AuthRepository.renewSession].
 *
 * Audiobookshelf rotates the refresh token when it renews a session. Playback has two legitimate ways to
 * discover the same expired access token at once: opening a new playback session and an already-buffering
 * Media3 range request. Letting those two exchanges run independently can send the same one-use refresh
 * token twice; whichever loses then looks like a real sign-out even though the other request just renewed
 * successfully.
 *
 * The refresh token is also the generation marker. A caller snapshots both stored tokens before it waits for
 * [renewalGate]. If another caller rotated them while it was waiting, the current access token is already the
 * result this caller wanted and no second refresh request is sent. This is deliberately below the playback
 * layer so library sync, playback-session open and stream recovery all share the same rule.
 */
internal class CoalescingAuthRepository(
    private val delegate: AuthRepository,
    private val tokens: SessionTokenProvider,
) : AuthRepository {
    private val renewalGate = Mutex()

    override suspend fun probeServer(serverUrl: String): AppResult<ServerCandidate> = delegate.probeServer(serverUrl)

    override suspend fun signIn(serverUrl: String, username: String, password: String): AppResult<Profile> =
        delegate.signIn(serverUrl, username, password)

    override suspend fun restoreSession(profileId: ProfileId): AppResult<SessionStatus> =
        delegate.restoreSession(profileId)

    override suspend fun renewSession(profileId: ProfileId): AppResult<SessionStatus> {
        val observedAccess = tokens.accessTokenFor(profileId)
        val observedRefresh = tokens.refreshTokenFor(profileId)
        return renewalGate.withLock {
            val currentAccess = tokens.accessTokenFor(profileId)
            val currentRefresh = tokens.refreshTokenFor(profileId)
            val credentialChanged = currentAccess != null &&
                (currentAccess != observedAccess || currentRefresh != observedRefresh)
            if (credentialChanged) {
                AppResult.Success(SessionStatus.Active)
            } else {
                delegate.renewSession(profileId)
            }
        }
    }

    override suspend fun refreshPermissions(profileId: ProfileId): AppResult<AccountState> =
        delegate.refreshPermissions(profileId)

    override suspend fun signOut(profileId: ProfileId): AppResult<Unit> = delegate.signOut(profileId)

    override suspend fun removeProfile(profileId: ProfileId): AppResult<Unit> = delegate.removeProfile(profileId)
}
