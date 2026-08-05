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
            authRepository.restoreSession(profileId)
        }
}
