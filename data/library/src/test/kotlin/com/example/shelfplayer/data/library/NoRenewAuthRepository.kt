package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerCandidate
import com.example.shelfplayer.core.model.auth.AccountState
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.domain.repository.AuthRepository

/** Renewal is outside the progress-chain scenarios; authentication failures stay failures. */
internal object NoRenewAuthRepository : AuthRepository {
    override suspend fun renewSession(profileId: ProfileId): AppResult<SessionStatus> =
        AppResult.Success(SessionStatus.ReauthenticationRequired)

    override suspend fun probeServer(serverUrl: String): AppResult<ServerCandidate> = unsupported()
    override suspend fun signIn(serverUrl: String, username: String, password: String): AppResult<Profile> =
        unsupported()
    override suspend fun restoreSession(profileId: ProfileId): AppResult<SessionStatus> = unsupported()
    override suspend fun requireReauthentication(profileId: ProfileId): AppResult<Unit> = unsupported()
    override suspend fun refreshPermissions(profileId: ProfileId): AppResult<AccountState> = unsupported()
    override suspend fun signOut(profileId: ProfileId): AppResult<Unit> = unsupported()
    override suspend fun removeProfile(profileId: ProfileId): AppResult<Unit> = unsupported()

    private fun <T> unsupported(): AppResult<T> =
        AppResult.Failure(AppError.ApiCompatibility(summary = "not part of this fake"))
}
