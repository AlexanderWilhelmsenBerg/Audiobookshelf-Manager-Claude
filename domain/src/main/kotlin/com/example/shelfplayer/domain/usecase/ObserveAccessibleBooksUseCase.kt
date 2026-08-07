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
 * PRODUCT_SPEC LIB-002 — the books the active profile may see, narrowed and ordered for display.
 *
 * The single book query in the app. It used to have a twin scoped to one library, and the two drifted
 * the moment filters arrived: the library one learned about them and this one did not, so the same
 * search returned different answers depending on which screen asked. `libraryId` is now the whole of
 * the difference between them.
 */
class ObserveAccessibleBooksUseCase @Inject constructor(
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
     * @param libraryId PRODUCT_SPEC 6.1 step 9 — one library, or `null` for every granted one. The
     *   caller resolves a stored default against the current grant first: a library the profile has
     *   lost must widen the shelf back to everything, not empty it.
     * @param filter narrows to a named shelf before ordering, and composes with [order] — "what I have
     *   not finished, by title" has to be askable.
     * @param focus narrows to one author or genre. Applied last, so it composes with both.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        query: String = "",
        order: BookSortOrder = BookSortOrder.LastPlayed,
        libraryId: LibraryId? = null,
        filter: BookFilter = BookFilter.Default,
        focus: BookFocus? = null,
    ): Flow<List<Book>> = profileRepository.observeActiveProfile().flatMapLatest { profile ->
        if (profile == null) {
            flowOf(emptyList())
        } else {
            libraryRepository.visibleBooks(profile.id, libraryId).map { books ->
                val narrowed = filterBooks(books, filter)
                    .filter { it.matchesQuery(query) }
                    .filter { focus == null || it.inGroup(focus.kind, focus.key) }
                sortBooks(narrowed, order)
            }
        }
    }.flowOn(defaultDispatcher)
}
