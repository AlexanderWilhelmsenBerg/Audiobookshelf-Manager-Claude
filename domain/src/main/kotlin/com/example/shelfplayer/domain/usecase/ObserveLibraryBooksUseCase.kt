package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.library.BookSortOrder
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
 * entity actually has. Matching happens on cached data so results appear immediately; enriching with
 * a server-side search is Phase 1.
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
                .map { books -> sortBooks(books.filter { it.matches(query) }, order) }
        }
    }
}

/**
 * PRODUCT_SPEC LIB-002 — title, subtitle, author, narrator, series and tags, when the data exists.
 *
 * ISBN and ASIN are part of the same requirement and join this predicate in Phase 1, when the sync
 * that populates them exists; matching against a field the fixture never fills would be a test that
 * proves nothing.
 */
private fun Book.matches(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    val haystack = buildList {
        add(title)
        subtitle?.let(::add)
        authors.forEach { add(it.name) }
        addAll(narrators)
        seriesMemberships.forEach { add(it.series.name) }
        addAll(tags)
        addAll(genres)
    }
    return haystack.any { it.contains(needle, ignoreCase = true) }
}
