package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.domain.library.AuthorShelf
import com.example.shelfplayer.domain.library.authorShelfFor
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
 * PRODUCT_SPEC §62 "author view" — one author, opened into their books.
 *
 * The same shape as [ObserveSeriesUseCase] and for the same reasons: reading `observeAccessibleBooks` means
 * the route carries only an author id, and the answer is filtered by the profile's grant twice over — item
 * visibility in the query, library grant in the repository. An author whose books have all been revoked
 * resolves to `null`, which the screen renders as "not available" rather than as a name with nothing under
 * it (PRODUCT_SPEC 5.2).
 */
class ObserveAuthorUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val libraryRepository: LibraryRepository,
    @param:Dispatcher(ShelfDispatcher.Default) private val defaultDispatcher: CoroutineDispatcher,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(authorId: AuthorId): Flow<AuthorShelf?> =
        profileRepository.observeActiveProfile().flatMapLatest { profile ->
            if (profile == null) {
                flowOf(null)
            } else {
                libraryRepository.observeAccessibleBooks(profile.id)
                    .map { books -> authorShelfFor(books, authorId) }
            }
        }.flowOn(defaultDispatcher)
}
