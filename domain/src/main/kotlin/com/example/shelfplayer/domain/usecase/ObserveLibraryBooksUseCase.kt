package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.library.BookFilter
import com.example.shelfplayer.domain.library.BookFocus
import com.example.shelfplayer.domain.library.BookSortOrder
import com.example.shelfplayer.domain.library.filterBooks
import com.example.shelfplayer.domain.library.inGroup
import com.example.shelfplayer.domain.library.matchesQuery
import com.example.shelfplayer.domain.library.sortBooks
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
 * PRODUCT_SPEC LIB-002 — the books of one library, ordered and filtered for display.
 *
 * The search term is matched against the fields PRODUCT_SPEC LIB-002 lists and that the cached
 * entity actually has, by the same predicate the all-libraries shelf uses
 * ([com.example.shelfplayer.domain.library.matchesQuery]).
 */
class ObserveLibraryBooksUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val libraryRepository: LibraryRepository,
    /**
     * PRODUCT_SPEC 16.3 — filtering and sorting run off the main thread.
     *
     * A collector in a ViewModel runs on `Dispatchers.Main`, so without this every keystroke sorted the
     * whole shelf there. On a 490-book library a device run felt it: "it takes a second to update".
     */
    @param:Dispatcher(ShelfDispatcher.Default) private val defaultDispatcher: CoroutineDispatcher,
) {
    /**
     * @param filter PRODUCT_SPEC LIB-002 — narrows to a named shelf before ordering. Composable with
     *   [order] on purpose: "what I have not finished, by title" is a reasonable thing to ask for.
     * @param focus narrows to one author or genre, which is how those axes open. Applied last, so it
     *   composes with both of the above rather than replacing them.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        libraryId: LibraryId,
        query: String = "",
        order: BookSortOrder = BookSortOrder.Default,
        filter: BookFilter = BookFilter.Default,
        focus: BookFocus? = null,
    ): Flow<List<Book>> = profileRepository.observeActiveProfile().flatMapLatest { profile ->
        if (profile == null) {
            flowOf(emptyList())
        } else {
            libraryRepository.observeBooks(profile.id, libraryId).map { books ->
                val narrowed = filterBooks(books, filter)
                    .filter { it.matchesQuery(query) }
                    .filter { focus == null || it.inGroup(focus.kind, focus.key) }
                sortBooks(narrowed, order)
            }
        }
    }.flowOn(defaultDispatcher)
}
