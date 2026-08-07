package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.domain.library.SeriesShelf
import com.example.shelfplayer.domain.library.groupIntoSeries
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
 * PRODUCT_SPEC LIB-003 / TC-16 — one series, opened into its ordered books.
 *
 * Reads from `observeAccessibleBooks` rather than from a library-scoped query so the route needs to
 * carry only the series id. That also means the answer is filtered by the profile's grant twice over —
 * item visibility in the query, library grant in the repository — and a series whose books have all
 * been revoked resolves to `null`, which the screen renders as "not available" rather than as an empty
 * series that still has a title.
 */
class ObserveSeriesUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val libraryRepository: LibraryRepository,
    @param:Dispatcher(ShelfDispatcher.Default) private val defaultDispatcher: CoroutineDispatcher,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(seriesId: SeriesId): Flow<SeriesShelf?> =
        profileRepository.observeActiveProfile().flatMapLatest { profile ->
            if (profile == null) {
                flowOf(null)
            } else {
                libraryRepository.observeAccessibleBooks(profile.id)
                    .map { books -> groupIntoSeries(books).firstOrNull { it.series.id == seriesId } }
            }
        }.flowOn(defaultDispatcher)
}
