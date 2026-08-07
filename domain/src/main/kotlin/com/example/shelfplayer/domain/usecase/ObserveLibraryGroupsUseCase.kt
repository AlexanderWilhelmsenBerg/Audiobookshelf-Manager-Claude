package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.domain.library.BookGroup
import com.example.shelfplayer.domain.library.BookGroupKind
import com.example.shelfplayer.domain.library.groupBooks
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
 * PRODUCT_SPEC LIB-002 — browsing one library by author or by genre.
 *
 * The same shape as [ObserveLibrarySeriesUseCase] and for the same reason: built on the book rows the
 * profile can see, so the grant is applied once in the query and every axis inherits it rather than
 * each re-deriving what is visible.
 */
class ObserveLibraryGroupsUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val libraryRepository: LibraryRepository,
    @param:Dispatcher(ShelfDispatcher.Default) private val defaultDispatcher: CoroutineDispatcher,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(libraryId: LibraryId, kind: BookGroupKind, query: String = ""): Flow<List<BookGroup>> =
        profileRepository.observeActiveProfile().flatMapLatest { profile ->
            if (profile == null) {
                flowOf(emptyList())
            } else {
                libraryRepository.observeBooks(profile.id, libraryId)
                    .map { books -> groupBooks(books, kind).filter { it.matchesQuery(query) } }
            }
        }.flowOn(defaultDispatcher)
}
