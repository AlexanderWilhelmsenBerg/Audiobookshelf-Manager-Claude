package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.library.nextInSeries
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * PRODUCT_SPEC 6.4 step 6 — the book to play when this one ends, or `null` for every reason there is none.
 *
 * ### Why the setting is read here
 *
 * So the caller has one question to ask rather than two, and cannot get the order wrong. The service asks
 * *"what should play next"* and gets either a book or nothing; whether the listener turned the feature off,
 * whether the book was in a series at all, and whether anything follows it are all the same answer at the
 * call site — which is the point, because a `STATE_ENDED` handler that has to branch on three separate
 * facts is one that will eventually branch on two.
 *
 * ### Accessibility is the repository's job, not this one's
 *
 * `observeAccessibleBooks` is already filtered by the profile's grant, so a book the profile may not see
 * is not in the list and cannot be returned. That is P1-01 doing its work rather than a check repeated
 * here — and repeating it is how the two would drift.
 */
class NextInSeriesUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val library: LibraryRepository,
    private val settings: PlaybackSettingsRepository,
    @param:Dispatcher(ShelfDispatcher.Default) private val defaultDispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(bookId: LibraryItemId): Book? = withContext(defaultDispatcher) {
        if (!settings.observeSettings().first().autoAdvanceSeries) return@withContext null
        val profileId = profiles.activeProfileId() ?: return@withContext null
        val books = library.observeAccessibleBooks(profileId).first()
        // `skipFinished = true`. A finished book resumes at its own end, so playing one would reach
        // `STATE_ENDED` immediately and advance again — a series of finished books would flick through
        // itself in a second and land somewhere arbitrary. Skipping them makes "carry on" mean the same
        // thing here as it does on the series screen's continue button.
        nextInSeries(books, bookId, skipFinished = true)
    }
}
