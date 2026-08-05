package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.library.BookSortOrder
import com.example.shelfplayer.domain.library.matchesQuery
import com.example.shelfplayer.domain.library.sortBooks
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * PRODUCT_SPEC LIB-002 — the books of one library, ordered and filtered for display.
 *
 * The search term is matched against the fields PRODUCT_SPEC LIB-002 lists and that the cached
 * entity actually has, by the same predicate the all-libraries shelf uses
 * ([com.example.shelfplayer.domain.library.matchesQuery]).
 */
class ObserveLibraryBooksUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val libraryRepository: LibraryRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        libraryId: LibraryId,
        query: String = "",
        order: BookSortOrder = BookSortOrder.Default,
    ): Flow<List<Book>> = profileRepository.observeActiveProfile().flatMapLatest { profile ->
        if (profile == null) {
            flowOf(emptyList())
        } else {
            libraryRepository.observeBooks(profile.id, libraryId)
                .map { books -> sortBooks(books.filter { it.matchesQuery(query) }, order) }
        }
    }
}
