package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.domain.library.SeriesShelf
import com.example.shelfplayer.domain.library.groupIntoSeries
import com.example.shelfplayer.domain.library.matchesQuery
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
 * PRODUCT_SPEC LIB-002 / LIB-003 — browsing one library by series instead of by book.
 *
 * Built on the same `observeBooks` flow the book list uses, so the two axes can never disagree about
 * what the profile is allowed to see: the visibility filter is applied once, in the query, and the
 * grouping happens after it.
 */
class ObserveLibrarySeriesUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val libraryRepository: LibraryRepository,
    /** PRODUCT_SPEC 16.3 — grouping a 490-book library is not main-thread work. */
    @param:Dispatcher(ShelfDispatcher.Default) private val defaultDispatcher: CoroutineDispatcher,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(libraryId: LibraryId, query: String = ""): Flow<List<SeriesShelf>> =
        profileRepository.observeActiveProfile().flatMapLatest { profile ->
            if (profile == null) {
                flowOf(emptyList())
            } else {
                libraryRepository.observeBooks(profile.id, libraryId)
                    .map { books -> groupIntoSeries(books).filter { it.matchesQuery(query) } }
            }
        }.flowOn(defaultDispatcher)
}
