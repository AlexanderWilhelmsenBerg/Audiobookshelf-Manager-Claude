package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.domain.download.DownloadScheduler
import com.example.shelfplayer.domain.download.OfflineFiles
import com.example.shelfplayer.domain.repository.DownloadRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** What a removal actually did. The caller must describe this, not what it hoped for. */
enum class DownloadRemoval {
    /** This was the last claim: any in-flight work was cancelled and the files were deleted. */
    FilesDeleted,

    /** Another profile still claims the copy: only this profile's claim was released. */
    ClaimReleased,

    /** This profile had no claim on the copy, so nothing was cancelled or deleted. */
    NotClaimed,
}

/**
 * PRODUCT_SPEC DL-001 / DL-003 / PD-003 — removing (or stopping) this profile's download.
 *
 * It releases this profile's claim first and only touches the filesystem if that was the last one, so
 * removing your copy of a book your partner is halfway through on the same device does nothing to theirs.
 *
 * ### A profile never stops another profile's transfer
 *
 * The scheduled work is shared per (server, item). It is cancelled only when no other profile claims the
 * copy: before the delete, so a running transfer cannot recreate what was just deleted. If the claim
 * snapshot said shared but the delete turned out to free the files (the other profile released in
 * between), the work is cancelled after the fact.
 *
 * Residual race: `cancelUniqueWork` is asynchronous, and between the claim read and the cancel another
 * profile could add a brand-new claim, whose work would then be cancelled. The window is tiny; the
 * start-up sweep and the Retry action recover from it.
 */
class RemoveDownloadUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val downloads: DownloadRepository,
    private val files: OfflineFiles,
    private val scheduler: DownloadScheduler,
) {

    /**
     * Removes this profile's copy, after the screen has confirmed it.
     *
     * @return what happened. A claim released on a shared copy is a complete, correct outcome, and
     *   reporting it as a failure would make the button look broken.
     */
    suspend operator fun invoke(bookId: LibraryItemId): AppResult<DownloadRemoval> {
        val profile = profiles.observeActiveProfile().first()
            ?: return AppResult.Failure(AppError.Authentication(summary = "Sign in to a server first."))
        val manifest = downloads.observe(profile.serverId, bookId).first()
        if (manifest == null || profile.id !in manifest.requestedBy) {
            return AppResult.Success(DownloadRemoval.NotClaimed)
        }
        val othersClaim = manifest.requestedBy.any { it != profile.id }
        if (!othersClaim) scheduler.cancel(profile.serverId, bookId)
        return when (val removed = files.remove(profile.id, profile.serverId, bookId)) {
            is AppResult.Failure -> AppResult.Failure(removed.error)

            is AppResult.Success -> if (removed.value) {
                if (othersClaim) scheduler.cancel(profile.serverId, bookId)
                AppResult.Success(DownloadRemoval.FilesDeleted)
            } else {
                AppResult.Success(DownloadRemoval.ClaimReleased)
            }
        }
    }

    /**
     * Stops the work and keeps the parts. Retained for the Book screen until it moves to Pause/Stop.
     *
     * The manifest is left alone, so the book still reads as *not downloaded* rather than vanishing.
     */
    suspend fun cancel(bookId: LibraryItemId): AppResult<Unit> {
        val profile = profiles.observeActiveProfile().first()
            ?: return AppResult.Failure(AppError.Authentication(summary = "Sign in to a server first."))
        scheduler.cancel(profile.serverId, bookId)
        return AppResult.Success(Unit)
    }
}
