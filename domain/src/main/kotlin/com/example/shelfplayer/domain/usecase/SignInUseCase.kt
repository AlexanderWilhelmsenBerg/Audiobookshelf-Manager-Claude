package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.flatMap
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.CapabilityRepository
import javax.inject.Inject

/**
 * PRODUCT_SPEC 6.1 — the whole of first launch after the user submits a password.
 *
 * The journey lists the order: authenticate, store the token, run a capability probe, synchronize, then
 * open home. The first three happen here; the sync is started by the screen that opens next, for the
 * reason below. Keeping the order in one place matters because a screen that ran the steps itself would own
 * a policy decision — "what happens when the handshake fails?" — that belongs in the domain layer
 * (PRODUCT_SPEC 9.1, 22.14).
 *
 * ### What is allowed to fail
 *
 * Only the sign-in itself is fatal. A profile whose password was accepted **is signed in**, and the
 * capability handshake that follows is a refinement: a failed one leaves the server's capabilities
 * unprobed, which SYNC-001 already defines as "everything unsupported" — features are disabled with an
 * explanation, not broken. Reporting it as a sign-in failure would send the user back to re-enter a
 * password that was correct, and — worse — would discard a session that is stored and valid.
 *
 * ### Why the initial sync is *not* here
 *
 * It used to be, awaited inline, and that was wrong in two ways a device run made obvious.
 *
 * A first sync of a real library is one request per item — a 490-book library is 490 requests, minutes of
 * work. Awaiting it here means the sign-in button spins for all of it, which PRODUCT_SPEC LIB-001
 * explicitly rules out: the home screen is supposed to render cached content while sync continues.
 *
 * Worse, this runs in the sign-in screen's `viewModelScope`, and a successful sign-in **pops** that screen.
 * The scope is cancelled, the sync dies part-way, and the `sync_state` row it wrote is left saying
 * `Syncing` for a sync nothing is running. That is the "library was empty until I pressed refresh" report,
 * three device runs in a row.
 *
 * The sync now belongs to the home screen, whose `onVisible` starts one for a profile that has never
 * synced and adopts one that was abandoned. Home outlives the navigation that reaches it, and its progress
 * is visible where the content will appear.
 */
class SignInUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val capabilityRepository: CapabilityRepository,
) {
    /**
     * Returns the saved profile, or the reason the credentials were refused.
     *
     * [SignInOutcome.warning] carries what went wrong *after* the credentials were accepted, so the UI can
     * say "signed in, but this server's capabilities could not be probed" instead of choosing between a lie
     * and an error (PRODUCT_SPEC 14.4: a user-facing error needs an impact and an action).
     */
    suspend operator fun invoke(serverUrl: String, username: String, password: String): AppResult<SignInOutcome> =
        authRepository.signIn(serverUrl, username, password).flatMap { profile ->
            // PRODUCT_SPEC SYNC-001 puts the handshake "on login". It is one request, so awaiting it does
            // not make the user wait in any meaningful sense — unlike the sync, which is one request per
            // item and now runs on the screen that shows its result.
            AppResult.Success(
                SignInOutcome(profile = profile, warning = capabilityRepository.handshake(profile.id).failureOrNull()),
            )
        }

    private fun AppResult<*>.failureOrNull(): AppError? = (this as? AppResult.Failure)?.error
}

/**
 * @property warning the first thing that failed after the credentials were accepted, or `null` when
 *   everything succeeded. The profile is signed in either way.
 */
data class SignInOutcome(val profile: Profile, val warning: AppError?)
