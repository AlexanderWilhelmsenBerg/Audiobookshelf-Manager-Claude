package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.PreferencesRepository
import com.example.shelfplayer.domain.repository.RememberedBookRepository
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
    private val preferences: PreferencesRepository,
    private val rememberedBooks: RememberedBookRepository,
) {
    suspend operator fun invoke(profileId: ProfileId): AppResult<Unit> {
        backgroundSync.cancel(profileId)
        // PRODUCT_SPEC SET-001 / BW-PLAY-01 — device-local profile state goes with the account. These
        // stores are outside Room, so no foreign key can clean them up. A server that later reissues the
        // same stable profile id must not inherit either the removed account's preferences or its book.
        // Cleanup failures deliberately do not block credential removal: stale local metadata is safer
        // than leaving an account credential in place because a best-effort housekeeping write failed.
        preferences.forget(profileId)
        rememberedBooks.forget(profileId)
        return authRepository.removeProfile(profileId)
    }
}
