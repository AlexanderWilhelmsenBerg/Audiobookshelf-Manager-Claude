package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.TrafficCategory
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
 * The active profile is resolved once at the start and that same identity owns the claim, asset lookup and
 * persistent WorkManager request. A later profile switch must not change who authorized already queued work.
 */
class DownloadBookUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val assets: BookAssetSource,
    private val downloads: DownloadRepository,
    private val scheduler: DownloadScheduler,
) {
    @Suppress("ReturnCount")
    suspend operator fun invoke(bookId: LibraryItemId, isAutomatic: Boolean = false): AppResult<Unit> {
        val profile = profiles.observeActiveProfile().first()
            ?: return AppResult.Failure(AppError.Authentication(summary = "Sign in to a server before downloading."))

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

        val existing = downloads.observe(profile.serverId, bookId).first()
        if (isAutomatic && existing?.state == DownloadState.Paused) return AppResult.Success(Unit)

        val requested = downloads.request(profile.serverId, bookId, profile.id, book.files)
        if (requested.isFailure()) return AppResult.Failure(requested.error)

        downloads.markQueued(profile.serverId, bookId)
        scheduler.enqueue(
            profileId = profile.id,
            serverId = profile.serverId,
            itemId = bookId,
            category = categoryFor(isAutomatic),
        )
        return AppResult.Success(Unit)
    }

    private fun categoryFor(isAutomatic: Boolean): TrafficCategory =
        if (isAutomatic) TrafficCategory.SmartDownload else TrafficCategory.ManualDownload

    private companion object {
        const val HEADROOM_DIVISOR = 10
    }
}
