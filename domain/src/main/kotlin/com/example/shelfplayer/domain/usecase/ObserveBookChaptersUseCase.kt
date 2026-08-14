package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * PRODUCT_SPEC PLAY-003 — one book's chapters, for a screen that is not the player.
 *
 * The player gets its chapters from the *session* it opened, which is the right source there: they arrive
 * with the thing being played. A book screen has no session, and needs them anyway — the history it shows
 * labels each position with the chapter it falls in, which is what makes a list of timestamps readable.
 *
 * Scoped to the active profile like every other read, because the visibility filter is per profile
 * (PRODUCT_SPEC 5.2) and a chapter list for a book this account cannot see is a chapter list it must not get.
 */
class ObserveBookChaptersUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val libraryRepository: LibraryRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(bookId: LibraryItemId): Flow<List<Chapter>> =
        profileRepository.observeActiveProfile().flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList()) else libraryRepository.observeChapters(profile.id, bookId)
        }
}
