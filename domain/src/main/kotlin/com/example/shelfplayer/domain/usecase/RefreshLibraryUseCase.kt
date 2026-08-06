package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import javax.inject.Inject

/**
 * PRODUCT_SPEC LIB-001 — pull-to-refresh and the initial synchronization.
 *
 * Refusing to run without an active profile is deliberate: a refresh with no profile would have to
 * guess which server to talk to, and PRODUCT_SPEC 5.2 requires every privileged operation to take an
 * explicit profile.
 *
 * ### Why the renewal policy lives here
 *
 * PRODUCT_SPEC AUTH-004 wants an expired session renewed without re-prompting, and PRODUCT_SPEC 9.1
 * puts policy in the domain layer. Putting it in `:data:library` instead would mean the library
 * repository knowing about credentials, and every future repository repeating the same dance. A use
 * case that already owns "what a refresh means" is the natural place for "and what an expired session
 * means during one".
 */
class RefreshLibraryUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val libraryRepository: LibraryRepository,
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(): AppResult<Int> {
        val profileId = profileRepository.activeProfileId()
            ?: return AppResult.Failure(
                AppError.Authentication(
                    summary = "Add a server profile before refreshing.",
                    requiresReauthentication = false,
                ),
            )

        val attempt = libraryRepository.refresh(profileId)
        if (attempt !is AppResult.Failure) return attempt
        if (attempt.error is AppError.Authentication) return renewAndRetry(profileId, original = attempt)
        // PRODUCT_SPEC 14.3 — "403: no retry; refresh permissions". The refresh is not a way to make this
        // attempt succeed; it is how the *next* one stops asking for something the account is no longer
        // allowed to have. Retrying would be asking the server the same rejected question, so the original
        // failure is what the caller gets back either way.
        if (attempt.error is AppError.Authorization) authRepository.refreshPermissions(profileId)
        return attempt
    }

    /**
     * PRODUCT_SPEC AUTH-004 — exactly one renewal, and at most one retry after it.
     *
     * The bound is the requirement, not a heuristic: "the app never loops login requests". A renewal
     * that succeeded earns one more attempt at the refresh; anything else returns the original `401`,
     * by which point [AuthRepository.renewSession] has already marked the profile so the UI can ask for
     * a password instead of retrying forever.
     *
     * The retry is *not* itself retried. If the second attempt also reports `401` — a token revoked
     * between the two calls, a server that renews and then rejects — that result is returned as it is.
     */
    private suspend fun renewAndRetry(profileId: ProfileId, original: AppResult.Failure): AppResult<Int> =
        when (authRepository.renewSession(profileId).statusOrNull()) {
            SessionStatus.Active -> libraryRepository.refresh(profileId)
            // A renewal that could not even be attempted — an unsaved profile — is reported as the
            // original authentication failure, which is the one the caller can act on.
            SessionStatus.ReauthenticationRequired, null -> original
        }

    private fun AppResult<SessionStatus>.statusOrNull(): SessionStatus? = when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> null
    }
}
