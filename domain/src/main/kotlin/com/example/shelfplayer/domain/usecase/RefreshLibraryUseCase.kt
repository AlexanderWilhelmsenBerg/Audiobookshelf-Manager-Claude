package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import javax.inject.Inject

/**
 * PRODUCT_SPEC LIB-001 — pull-to-refresh and the initial synchronization.
 *
 * Refusing to run without an active profile is deliberate: a refresh with no profile would have to
 * guess which server to talk to, and PRODUCT_SPEC 5.2 requires every privileged operation to take an
 * explicit profile.
 */
class RefreshLibraryUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val libraryRepository: LibraryRepository,
) {
    suspend operator fun invoke(): AppResult<Int> {
        val profileId = profileRepository.activeProfileId()
            ?: return AppResult.Failure(
                AppError.Authentication(
                    summary = "Add a server profile before refreshing.",
                    requiresReauthentication = false,
                ),
            )
        return libraryRepository.refresh(profileId)
    }
}
