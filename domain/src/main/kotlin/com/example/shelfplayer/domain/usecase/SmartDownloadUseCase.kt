package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.download.DownloadHousekeeping
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.download.OfflineFiles
import com.example.shelfplayer.domain.library.booksInSeriesOrder
import com.example.shelfplayer.domain.library.nextInSeries
import com.example.shelfplayer.domain.library.primarySeriesMembership
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * PRODUCT_SPEC DL-005 / ADR-0018 decision 1 — fetch the next book in the series at the halfway mark.
 *
 * The owner's words: *"50% in book 6 will trigger download of book 7… If smart download is on, you can also
 * have the option to delete the previous book when the new is downloaded. So if book 6 is 50%, trigger
 * download of 7, and delete book 5."*
 *
 * > **Deviation from DL-005**, recorded in ADR-0018. PRODUCT_SPEC assigns smart download to Phase 4 and
 * > describes a different trigger — on *finishing* a book. The owner moved it here and chose the halfway
 * > mark, which is the better rule for the case it exists for: a book finished on a train is finished
 * > wherever the listener happens to be, and starting the next download then is exactly too late.
 *
 * ### Called from the progress journal, and cheap when off
 *
 * `DefaultPlaybackRepository.recordPosition` runs every few seconds while a book plays, so the first thing
 * this does is read one boolean. Everything else — the series lookup, the catalogue scan — happens only
 * once the setting is on **and** the position has crossed the mark.
 *
 * ### Crossing, not being past
 *
 * The trigger is the *transition* over halfway: the previous position was before it and this one is after.
 * A listener who is two-thirds through and seeks backwards and forwards would otherwise re-enqueue on every
 * journal write. `DownloadScheduler.enqueue` is idempotent, so the cost of getting this wrong is small, but
 * "the app decided to download something" is a thing that should happen once per book and be findable in
 * the log as one line.
 */
class SmartDownloadUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val library: LibraryRepository,
    private val settings: PlaybackSettingsRepository,
    private val downloadBook: DownloadBookUseCase,
    private val files: OfflineFiles,
    private val logger: Logger,
) {

    /**
     * Considers [bookId] at [position] of [duration], and acts if the halfway mark has just been crossed.
     *
     * @param previousPosition where the listener was on the last journal write, in the same units.
     */
    suspend operator fun invoke(bookId: LibraryItemId, previousPosition: Long, position: Long, duration: Long) {
        val housekeeping = settings.observeHousekeeping().first()
        if (!housekeeping.smartDownload || duration <= 0) return
        if (!crossedHalfway(previousPosition, position, duration)) return
        act(bookId, housekeeping.deletePreviousOnSmartDownload)
    }

    /**
     * The part that reads the catalogue, once the trigger has fired.
     *
     * Split from the trigger so the hot path above is three cheap tests and a call: that one runs every few
     * seconds for the whole length of every book anybody plays, and this runs at most once per book.
     */
    @Suppress("ReturnCount")
    private suspend fun act(bookId: LibraryItemId, deletePrevious: Boolean) {
        val profileId = profiles.activeProfileId() ?: return
        val books = library.observeAccessibleBooks(profileId).first()
        val current = books.firstOrNull { it.id == bookId } ?: return
        // Shared with auto-advance (PLAY-005) rather than resolved here. Two copies of "what comes after
        // this book" would eventually fetch book 7 and play book 8; see `NextInSeries`.
        val membership = current.primarySeriesMembership() ?: return
        val ordered = booksInSeriesOrder(books, membership)

        val index = ordered.indexOfFirst { book -> book.id == bookId }
        if (index < 0) return

        // `skipFinished = false`: fetching the immediate next book is the point even when it happens to be
        // finished already, because DL-005's retention rules decide what to keep and this only decides what
        // to bring down. Auto-advance passes true, for a reason its own call site gives.
        nextInSeries(books, bookId, skipFinished = false)?.let { next ->
            logger.info(LogCategory.Sync, "Halfway through a book, so the next in its series is being fetched")
            // `isAutomatic`: nobody pressed anything, so a paused book stays paused (DL-001).
            downloadBook(next.id, isAutomatic = true)
        }

        if (deletePrevious) {
            removePrevious(ordered, index, bookId)
        }
    }

    /**
     * Decision 7's second half — the book *before* the one being listened to.
     *
     * `index - 1`, deliberately, not the one just finished. A listener halfway through book 6 may well go
     * back a chapter; book 5 is the one they have demonstrably moved on from. Removing the current book
     * would be the app deleting what is playing, which DL-006 forbids outright.
     */
    private suspend fun removePrevious(ordered: List<Book>, index: Int, current: LibraryItemId) {
        val previous = ordered.getOrNull(index - 1) ?: return
        if (previous.id == current) return
        val profile = profiles.observeActiveProfile().first() ?: return
        val removed = files.remove(profile.id, profile.serverId, previous.id)
        if (removed is AppResult.Success && removed.value) {
            logger.info(LogCategory.Sync, "The previous book in the series was removed to make room")
        }
    }

    private fun crossedHalfway(previous: Long, position: Long, duration: Long): Boolean {
        val mark = (duration * DownloadHousekeeping.SMART_DOWNLOAD_AT).toLong()
        return previous < mark && position >= mark
    }
}
