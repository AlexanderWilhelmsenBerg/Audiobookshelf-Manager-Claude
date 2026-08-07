package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.domain.library.HomeShelves
import com.example.shelfplayer.domain.library.homeShelvesOf
import com.example.shelfplayer.domain.library.visibleBooks
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * PRODUCT_SPEC LIB-002 — the three shelves the home screen opens on.
 *
 * Reads the same visible rows every other axis reads, so the grant is applied once and the shelves
 * cannot show a book the flat list would hide.
 */
class ObserveHomeShelvesUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val libraryRepository: LibraryRepository,
    /** PRODUCT_SPEC 16.3 — three sorts and a series grouping over the whole library, off the main thread. */
    @param:Dispatcher(ShelfDispatcher.Default) private val defaultDispatcher: CoroutineDispatcher,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(libraryId: LibraryId? = null): Flow<HomeShelves> =
        profileRepository.observeActiveProfile().flatMapLatest { profile ->
            if (profile == null) {
                flowOf(HomeShelves.Empty)
            } else {
                libraryRepository.visibleBooks(profile.id, libraryId).map { books -> homeShelvesOf(books) }
            }
        }.flowOn(defaultDispatcher)
}
