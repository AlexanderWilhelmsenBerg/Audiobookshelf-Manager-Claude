package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.isFailure
import com.example.shelfplayer.domain.download.BookAssetSource
import com.example.shelfplayer.domain.download.DownloadScheduler
import com.example.shelfplayer.domain.repository.DownloadRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * PRODUCT_SPEC DL-001 — what happens when somebody presses *Download*.
 *
 * Four steps, in this order, and the order is the design:
 *
 * 1. **May this account download at all?** The server's grant, persisted in slice 1. Checked first because
 *    it is the only one whose answer cannot change by trying again.
 * 2. **Is there room?** *"Before queuing, app checks estimated size and free space."* Before, not during: a
 *    download that fills the disk and then fails has already made the device unusable for a while, and the
 *    check costs one `statfs`.
 * 3. **Record the claim.** The manifest and this profile's reference, which is also what makes a second tap
 *    harmless and what lets a second profile share a copy without fetching it again.
 * 4. **Enqueue the work**, which outlives the screen.
 *
 * Nothing here transfers anything. That is the worker's job, and keeping the decision separate from the
 * transfer is what lets every one of these refusals be tested without a server.
 */
class DownloadBookUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val assets: BookAssetSource,
    private val downloads: DownloadRepository,
    private val scheduler: DownloadScheduler,
) {

    /**
     * @return the reason it will not happen, or success once the work is queued. Success means *queued*,
     *   not *downloaded* — the manifest is the thing to observe afterwards.
     */
    /**
     * @param isAutomatic whether nothing was pressed — smart download, or a sweep. **A paused book is
     *   skipped when this is true**, and only then. A listener who stopped a download on a metered train
     *   must not have it restarted by the app noticing Wi-Fi; the same listener pressing *Resume* is
     *   asking for exactly that, so the manual path clears the pause instead.
     */
    @Suppress("ReturnCount")
    suspend operator fun invoke(bookId: LibraryItemId, isAutomatic: Boolean = false): AppResult<Unit> {
        val profile = profiles.observeActiveProfile().first()
            ?: return AppResult.Failure(AppError.Authentication(summary = "Sign in to a server before downloading."))

        // PRODUCT_SPEC DL-001 criterion 1. The button is already hidden for an account without the grant, so
        // reaching here means a deep link, a stale screen, or a permission revoked since the screen loaded.
        if (!profile.canDownload) {
            return AppResult.Failure(
                AppError.Authorization(summary = "Your server does not allow this account to download."),
            )
        }

        val planned = assets.assetsFor(profile.id, bookId)
        if (planned.isFailure()) return AppResult.Failure(planned.error)
        val book = (planned as AppResult.Success).value
        if (book.files.isEmpty()) {
            return AppResult.Failure(
                AppError.Unknown(summary = "This book has no audio files to download."),
            )
        }

        val required = book.estimatedBytes + (book.estimatedBytes / HEADROOM_DIVISOR)
        val free = downloads.freeBytes()
        if (free in 1 until required) {
            return AppResult.Failure(
                AppError.Storage(summary = "There is not enough space for this book.", freeBytes = free),
            )
        }

        // PRODUCT_SPEC DL-001 — an unattended caller leaves a paused book alone.
        //
        // Checked here rather than in the scheduler because it is a *policy* about who asked, and the
        // scheduler cannot tell a listener's tap from a sweep. Success rather than a failure: nothing went
        // wrong, and a smart-download pass that reported an error for every book the user had paused would
        // be noise about the app working correctly.
        val existing = downloads.observe(profile.serverId, bookId).first()
        if (isAutomatic && existing?.state == DownloadState.Paused) return AppResult.Success(Unit)

        val requested = downloads.request(profile.serverId, bookId, profile.id, book.files)
        if (requested.isFailure()) return AppResult.Failure(requested.error)

        // `request` deliberately leaves an existing manifest untouched — it is the shared-copy path — so
        // resuming has to lift the pause itself. A no-op for anything that is not paused.
        downloads.markQueued(profile.serverId, bookId)
        scheduler.enqueue(profile.serverId, bookId)
        return AppResult.Success(Unit)
    }

    private companion object {
        /**
         * Ten per cent on top of the estimate, because the estimate is the *scan's* view of the files.
         *
         * A server that has re-encoded or re-tagged since its last scan sends something else, and a download
         * that stops at ninety-nine per cent for want of a few megabytes is the worst possible outcome — it
         * has already spent the whole transfer. Refusing slightly too eagerly costs a user one message.
         */
        const val HEADROOM_DIVISOR = 10
    }
}
