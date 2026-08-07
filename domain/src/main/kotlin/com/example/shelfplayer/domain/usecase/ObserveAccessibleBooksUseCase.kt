package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.library.BookSortOrder
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
 * PRODUCT_SPEC LIB-002 — every book the active profile may see, across all of its libraries.
 *
 * This is what the app opens on. A list of libraries is a hop the user did not ask for — with one
 * library it is a single card standing between them and their books, and with several it is still a
 * menu rather than a shelf. Browsing *by library* remains available and is a setting
 * (PRODUCT_SPEC SET-002, Profiles).
 *
 * The default order is [BookSortOrder.LastPlayed], so the book being listened to is the first thing on
 * screen.
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
     * @param libraryId PRODUCT_SPEC 6.1 step 9 — narrows the shelf to one library. `null`, the default,
     *   is every library the profile is granted. The caller resolves a stored default against the
     *   current grant before passing it: a library the profile has lost must widen the shelf back to
     *   everything, not empty it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        query: String = "",
        order: BookSortOrder = BookSortOrder.LastPlayed,
        libraryId: LibraryId? = null,
    ): Flow<List<Book>> = profileRepository.observeActiveProfile().flatMapLatest { profile ->
        if (profile == null) {
            flowOf(emptyList())
        } else {
            val source = if (libraryId == null) {
                libraryRepository.observeAccessibleBooks(profile.id)
            } else {
                libraryRepository.observeBooks(profile.id, libraryId)
            }
            source.map { books -> sortBooks(books.filter { it.matchesQuery(query) }, order) }
        }
    }.flowOn(defaultDispatcher)
}
