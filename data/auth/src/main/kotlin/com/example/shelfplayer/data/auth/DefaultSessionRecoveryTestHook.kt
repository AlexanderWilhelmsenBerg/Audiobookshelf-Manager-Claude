package com.example.shelfplayer.data.auth

import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.SessionRecoveryTestHook
import javax.inject.Inject

/**
 * The one implementation, and it is deliberately three lines.
 *
 * Everything that could go wrong belongs to `SessionTokenProvider.invalidateAccessTokenForRecoveryTest` —
 * the refresh-token precondition, leaving a background profile's cache alone — so this only resolves *which*
 * profile is meant. See [SessionRecoveryTestHook] for why the seam exists.
 */
class DefaultSessionRecoveryTestHook @Inject constructor(
    private val profiles: ProfileRepository,
    private val tokens: SessionTokenProvider,
) : SessionRecoveryTestHook {
    override suspend fun expireAccessToken(): Boolean {
        val profileId = profiles.activeProfileId() ?: return false
        return tokens.invalidateAccessTokenForRecoveryTest(profileId)
    }
}
