package com.example.shelfplayer.data.auth

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC AUTH-002 / AUTH-004 — makes the saved active profile's credential current on a cold start.
 *
 * This replaces the demo-library bootstrapper. The two do the same *kind* of job — one thing that has to
 * happen once, early, before any screen asks for data — but nothing else: seeding wrote a fixture library
 * into Room, while this only loads a stored token into memory. No network call and no database write
 * happen unless the token turns out to be unusable, in which case `AUTH-004` marks the profile.
 *
 * A profile that cannot be restored is **not** signed out and **not** removed. Its cached library stays
 * browsable offline (PRODUCT_SPEC 6.3) and the UI asks for a password when the user next needs the
 * network.
 */
@Singleton
class SessionRestorer @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
    private val logger: Logger,
) {
    /**
     * Returns the status of the active profile's session, or `null` when no profile is selected.
     *
     * `null` is the first-launch state and the state after the last profile is removed. It is what tells
     * the UI to show onboarding rather than an empty library.
     */
    suspend fun restoreActiveSession(): SessionStatus? {
        val profileId = profileRepository.activeProfileId() ?: return null
        val status = when (val result = authRepository.restoreSession(profileId)) {
            is AppResult.Success -> result.value
            // A profile id that no longer resolves to a saved profile — removed on another screen,
            // or a settings file that outlived its database. Reported as needing sign-in rather than
            // retried, because there is nothing to retry.
            is AppResult.Failure -> SessionStatus.ReauthenticationRequired
        }
        logger.info(
            LogCategory.Auth,
            "Restored the active profile's session",
            LogField.Identifier("profile", profileId.value),
            LogField.Public("status", status.name),
        )
        return status
    }
}
