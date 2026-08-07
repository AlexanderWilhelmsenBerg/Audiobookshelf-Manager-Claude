package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import javax.inject.Inject

/**
 * PRODUCT_SPEC LIB-002 — "local cached results appear immediately; server search may enrich results".
 *
 * The enrichment, expressed as the two-step it actually is: the cached results are already on screen
 * from Room, and this asks the server whether it knows about anything the cache does not. What comes
 * back is written to Room, so the new hits arrive on the same stream as the old ones and the screen
 * never has to reconcile two lists.
 *
 * ### Why a missing profile is success, not failure
 *
 * Unlike [RefreshLibraryUseCase], nothing the user asked for fails when this cannot run. They typed
 * into a search field and the cached matches appeared; the server pass is an addition. Reporting an
 * error for it would put a failure banner over a search that worked.
 */
class SearchServerUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val libraryRepository: LibraryRepository,
) {
    suspend operator fun invoke(query: String): AppResult<Int> {
        val profileId = profileRepository.activeProfileId() ?: return AppResult.Success(0)
        return libraryRepository.searchServer(profileId, query)
    }
}
