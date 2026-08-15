package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.download.OfflineFiles
import com.example.shelfplayer.domain.repository.DownloadRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.first
import java.time.Duration
import javax.inject.Inject
import com.example.shelfplayer.core.common.log.info as logInfo

/**
 * PRODUCT_SPEC DL-006 / ADR-0018 decision 7 — removing a finished book, some days after it was finished.
 *
 * The owner's words: *"To delete books after they are finished. There should be a when settings, so delete
 * after x days after finished."*
 *
 * ### DL-006's protective half is the important half
 *
 * The requirement names three books that must never be removed automatically, and each is here as an
 * explicit refusal rather than as an assumption:
 *
 *  - **the book that is playing** — deleting the file under a running player is the worst outcome in this
 *    app, and it is also the one that is easiest to cause by accident;
 *  - **a book with unsynced progress** — its listening exists only on this device until the outbox drains,
 *    and the files are what a re-sync would be about (product priority 2);
 *  - **a pinned download** — the user said no, once, deliberately.
 *
 * ### It never runs without being switched on
 *
 * `deleteFinishedAfterDays` is zero by default, and zero is off. Deleting somebody's audiobook because a
 * timer expired is the most destructive thing this app can do unattended.
 */
class CleanUpDownloadsUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val library: LibraryRepository,
    private val downloads: DownloadRepository,
    private val files: OfflineFiles,
    private val settings: PlaybackSettingsRepository,
    private val clock: AppClock,
    private val logger: Logger,
) {

    /**
     * @param playingBookId what the player currently holds, or `null`. Passed in rather than read, because
     *   the player is in `:playback` and this is domain — and because "what is playing" is a fact the
     *   caller has and this cannot obtain without a dependency that would invert the layering.
     * @return how many books were removed.
     */
    suspend operator fun invoke(playingBookId: LibraryItemId? = null): AppResult<Int> {
        val housekeeping = settings.observeHousekeeping().first()
        if (!housekeeping.deletesFinished) return AppResult.Success(0)

        val profile = profiles.observeActiveProfile().first() ?: return AppResult.Success(0)
        val books = library.observeAccessibleBooks(profile.id).first().associateBy(Book::id)
        val stored = downloads.observeAll().first()
        val cutoff = Duration.ofDays(housekeeping.deleteFinishedAfterDays.toLong())

        var removed = 0
        stored.filter { it.isComplete }.forEach { copy ->
            if (!isRemovable(copy, books[copy.itemId], playingBookId, cutoff)) return@forEach
            val result = files.remove(profile.id, profile.serverId, copy.itemId)
            if (result is AppResult.Success && result.value) removed++
        }

        if (removed > 0) {
            logger.logInfo(
                LogCategory.Sync,
                "Finished books were removed to reclaim space",
                LogField.Count("books", removed),
                LogField.Count("afterDays", housekeeping.deleteFinishedAfterDays),
            )
        }
        return AppResult.Success(removed)
    }

    /**
     * DL-006's four questions, in the order that makes the refusals cheapest.
     *
     * The catalogue row can be absent — a book removed upstream, or one this profile has lost access to —
     * and that is *not* a reason to delete: PRODUCT_SPEC 5.2 says the profile may not see it, not that its
     * bytes are rubbish, and DL-003 says losing access does not delete downloads. It is left alone and the
     * storage screen lists it untitled, where a person can remove it deliberately.
     */
    private fun isRemovable(copy: OfflineBook, book: Book?, playingBookId: LibraryItemId?, cutoff: Duration): Boolean {
        val progress = book?.progress ?: return false
        return copy.itemId != playingBookId &&
            !copy.isPinned &&
            progress.isFinished &&
            // Product priority 2. Listening that exists only here is listening the server has never seen,
            // and the local copy is what a re-sync would be about.
            !progress.hasUnsyncedChanges &&
            Duration.between(progress.updatedAt, clock.now()) >= cutoff
    }
}
