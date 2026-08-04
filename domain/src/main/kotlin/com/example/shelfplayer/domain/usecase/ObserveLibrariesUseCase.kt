package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * PRODUCT_SPEC LIB-001 — the libraries the *active* profile can see.
 *
 * Binding the query to the active profile here, rather than in each screen, is what makes
 * PRODUCT_SPEC 5.2 ("unauthorized libraries never appear") a property of the app instead of
 * something every new screen has to remember.
 */
class ObserveLibrariesUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val libraryRepository: LibraryRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<List<Library>> = profileRepository.observeActiveProfile().flatMapLatest { profile ->
        if (profile == null) flowOf(emptyList()) else libraryRepository.observeLibraries(profile.id)
    }
}
