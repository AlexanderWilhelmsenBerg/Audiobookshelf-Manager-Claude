package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.core.model.flatMap
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import javax.inject.Inject

/**
 * PRODUCT_SPEC 6.5 / AUTH-002 — changing which profile the app is acting as.
 *
 * ### Order matters, and this is the order
 *
 * The selection is written **first**, then the credential is loaded. That looks backwards and is not: every
 * screen reads its content from Room keyed by the active profile, so writing the selection is what makes
 * the UI show the new account. Loading the credential first would leave a window in which requests were
 * signed as B while the screens still showed A's library — the cross-profile confusion product priority 4
 * exists to prevent.
 *
 * A profile whose credential will not load still switches. PRODUCT_SPEC 6.3 keeps its cached library
 * browsable offline and AUTH-004 keeps its downloads playable; the session state tells the UI to ask for a
 * password when the network is next needed. Refusing the switch would leave the user unable to reach an
 * account they can see in the switcher.
 *
 * ### What Phase 2 adds here
 *
 * PRODUCT_SPEC 6.5 also requires active progress to be flushed locally and remotely before the swap, and
 * playback to pause. Neither exists yet — there is no player — and this class is where both belong when
 * they do. It is named as the seam so nobody adds a second switching path later.
 */
class SwitchProfileUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(profileId: ProfileId): AppResult<SessionStatus> =
        profileRepository.setActiveProfile(profileId).flatMap {
            authRepository.restoreSession(profileId).also { status ->
                if (status.isActive()) refreshPermissions(profileId)
            }
        }

    /**
     * PRODUCT_SPEC 5.2 — the incoming account's grant, re-read before its content fills the screen.
     *
     * The result is deliberately discarded. The switch has already happened by this point and must not be
     * undone by a server that could not be reached: PRODUCT_SPEC 6.3 keeps a profile's cached library
     * browsable offline, and failing the switch would strand the user on an account they were leaving.
     * What the call is *for* is the success case — a grant changed on the server since this profile last
     * signed in, which is otherwise never noticed — and the marking that `refreshPermissions` performs
     * when the server actively rejects the session.
     *
     * Only attempted for a session that restored. A profile already needing reauthentication has no token
     * to ask with, and asking anyway would spend a network round trip to be told what is already known.
     *
     * This is cheap on purpose. AUTH-002 allows 500 ms for a switch, so the library sync that has to
     * follow it belongs to the screen that shows the result, not to this call.
     */
    private suspend fun refreshPermissions(profileId: ProfileId) {
        authRepository.refreshPermissions(profileId)
    }

    private fun AppResult<SessionStatus>.isActive(): Boolean =
        this is AppResult.Success && value == SessionStatus.Active
}
