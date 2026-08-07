package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.sync.BackgroundSync
import javax.inject.Inject

/**
 * PRODUCT_SPEC AUTH-002 / SYNC-003 — removing a profile, and everything scheduled on its behalf.
 *
 * A use case rather than a line in the repository, because the two halves live in different layers:
 * the credential and the rows belong to the auth repository, and the schedule belongs to the platform.
 * Composing them here is what keeps `BackgroundSync` off `DefaultAuthRepository`'s constructor.
 *
 * The work is cancelled **first**. A schedule outliving its profile is a device woken every six hours
 * to sync an account that no longer exists, failing each time, for as long as the app is installed —
 * and the reverse ordering leaves exactly that behind if the removal succeeds and the process dies
 * before the cancel.
 */
class RemoveProfileUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val backgroundSync: BackgroundSync,
) {
    suspend operator fun invoke(profileId: ProfileId): AppResult<Unit> {
        backgroundSync.cancel(profileId)
        return authRepository.removeProfile(profileId)
    }
}
