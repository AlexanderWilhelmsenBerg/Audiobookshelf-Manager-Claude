package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.domain.download.DownloadScheduler
import com.example.shelfplayer.domain.download.OfflineFiles
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * PRODUCT_SPEC DL-001 / DL-003 / 21 — stopping a download, and removing one.
 *
 * ### Two verbs, and conflating them would lose somebody's evening
 *
 * **Cancel** stops the work and keeps every byte already on disk. A user who pressed it on a train has not
 * asked to throw away the eighty per cent they have; the partial files are what the next attempt resumes
 * from, and after `If-Range` landed in slice 3 that resume is safe rather than hopeful.
 *
 * **Remove** deletes. It releases this profile's claim first and only touches the filesystem if that was the
 * last one — DL-003 criteria 4 and 5 — so removing your copy of a book your partner is halfway through on
 * the same device does nothing to theirs.
 *
 * Both cancel the work, because a running transfer would otherwise re-create what was just deleted.
 */
class RemoveDownloadUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val files: OfflineFiles,
    private val scheduler: DownloadScheduler,
) {

    /**
     * Removes this profile's copy, after the screen has confirmed it.
     *
     * @return success whether or not any bytes went — a claim released on a shared copy is a complete,
     *   correct outcome, and reporting it as a failure would make the button look broken.
     */
    suspend operator fun invoke(bookId: LibraryItemId): AppResult<Unit> {
        val profile = profiles.observeActiveProfile().first()
            ?: return AppResult.Failure(AppError.Authentication(summary = "Sign in to a server first."))
        scheduler.cancel(profile.serverId, bookId)
        return when (val removed = files.remove(profile.id, profile.serverId, bookId)) {
            is AppResult.Failure -> AppResult.Failure(removed.error)
            is AppResult.Success -> AppResult.Success(Unit)
        }
    }

    /**
     * Stops the work and keeps the parts.
     *
     * The manifest is left alone too, so the book still reads as *not downloaded* rather than vanishing, and
     * the next tap resumes rather than starting over.
     */
    suspend fun cancel(bookId: LibraryItemId): AppResult<Unit> {
        val profile = profiles.observeActiveProfile().first()
            ?: return AppResult.Failure(AppError.Authentication(summary = "Sign in to a server first."))
        scheduler.cancel(profile.serverId, bookId)
        return AppResult.Success(Unit)
    }
}
