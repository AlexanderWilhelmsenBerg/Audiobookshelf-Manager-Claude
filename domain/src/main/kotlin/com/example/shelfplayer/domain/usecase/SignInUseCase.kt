package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.flatMap
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.CapabilityRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import javax.inject.Inject

/**
 * PRODUCT_SPEC 6.1 — the whole of first launch after the user submits a password.
 *
 * The journey lists the order: authenticate, store the token, run a capability probe, synchronize, then
 * open home. This is that order, in one place, because a screen that ran the three steps itself would own
 * a policy decision — "what happens when the handshake succeeds but the sync fails?" — that belongs in the
 * domain layer (PRODUCT_SPEC 9.1, 22.14).
 *
 * ### What is allowed to fail
 *
 * Only the sign-in itself is fatal. A profile whose password was accepted **is signed in**, and the two
 * steps after it are refinements:
 *
 * - a failed capability handshake leaves the server's capabilities unprobed, which SYNC-001 already
 *   defines as "everything unsupported" — features are disabled with an explanation, not broken;
 * - a failed initial sync leaves an empty cached library, which LIB-001 already handles as a visible,
 *   non-blocking sync state with a retry.
 *
 * Reporting either as a sign-in failure would send the user back to re-enter a password that was correct,
 * and — worse — would discard a session that is stored and valid.
 */
class SignInUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val capabilityRepository: CapabilityRepository,
    private val libraryRepository: LibraryRepository,
) {
    /**
     * Returns the saved profile, or the reason the credentials were refused.
     *
     * [SignInOutcome.warning] carries what went wrong *after* a successful sign-in, so the UI can say
     * "signed in, but your library could not be loaded yet" instead of choosing between a lie and an error
     * (PRODUCT_SPEC 14.4: a user-facing error needs an impact and an action).
     */
    suspend operator fun invoke(serverUrl: String, username: String, password: String): AppResult<SignInOutcome> =
        authRepository.signIn(serverUrl, username, password).flatMap { profile ->
            // The handshake runs first: PRODUCT_SPEC SYNC-001 puts it "on login", and a sync that ran
            // before it would make capability-gated decisions against an unprobed server.
            //
            // Both steps run regardless of the other's outcome, and the two `val`s rather than an `?:`
            // chain are what makes that true: `?:` would short-circuit and skip the sync whenever the
            // handshake failed, leaving a signed-in profile with an empty library for a reason that has
            // nothing to do with its library. A server whose capabilities are unknown is still browsable.
            val handshakeFailure = capabilityRepository.handshake(profile.id).failureOrNull()
            val syncFailure = libraryRepository.refresh(profile.id).failureOrNull()
            AppResult.Success(
                SignInOutcome(
                    profile = profile,
                    // The earlier failure is the one shown. A cascade of consequences is not more
                    // informative than its cause (PRODUCT_SPEC 14.4).
                    warning = handshakeFailure ?: syncFailure,
                ),
            )
        }

    private fun AppResult<*>.failureOrNull(): AppError? = (this as? AppResult.Failure)?.error
}

/**
 * @property warning the first thing that failed after the credentials were accepted, or `null` when
 *   everything succeeded. The profile is signed in either way.
 */
data class SignInOutcome(val profile: Profile, val warning: AppError?)
