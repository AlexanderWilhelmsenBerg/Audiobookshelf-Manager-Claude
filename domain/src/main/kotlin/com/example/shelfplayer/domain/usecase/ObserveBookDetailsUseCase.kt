package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/** PRODUCT_SPEC LIB-004 — one book, scoped to the active profile's own progress. */
class ObserveBookDetailsUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val libraryRepository: LibraryRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(bookId: LibraryItemId): Flow<Book?> =
        profileRepository.observeActiveProfile().flatMapLatest { profile ->
            if (profile == null) flowOf(null) else libraryRepository.observeBook(profile.id, bookId)
        }
}
