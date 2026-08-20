package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.domain.download.DownloadScheduler
import com.example.shelfplayer.domain.repository.DownloadRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * PRODUCT_SPEC DL-001 — stop a running download without throwing away what it has fetched.
 *
 * ### Why this existed as a capability before it existed as a button
 *
 * `docs/gaps.md` put it plainly: *"A download can be cancelled and retried, which resumes from the `.part`,
 * so the capability exists — there is no button that says pause."* Every mechanical piece was already
 * here. What was missing was the app's ability to tell the two apart afterwards.
 *
 * Cancel-then-retry leaves the manifest reading `Failed`, because that is what the worker records when its
 * job stops. So a listener who deliberately stopped a download on a train saw **"Download failed"** under
 * it, with a retry button — the app apologising for having obeyed, and offering to undo something nobody
 * regretted. `DownloadState.Paused` is the whole difference, and it has to be *stored* because the
 * distinction must survive the restart that separates pausing at night from looking at the list in the
 * morning.
 *
 * ### The order matters
 *
 * The state is written **first**, and then the work is cancelled. The other way round leaves a window in
 * which the worker's own failure handler runs against a manifest that still says `Running`, and records
 * `Failed` over the top of the pause that was about to be written. That window is short and it is exactly
 * the one that a slow device makes long.
 */
class PauseDownloadUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val downloads: DownloadRepository,
    private val scheduler: DownloadScheduler,
) {

    suspend operator fun invoke(bookId: LibraryItemId): AppResult<Unit> {
        val profile = profiles.observeActiveProfile().first()
            ?: return AppResult.Failure(AppError.Authentication(summary = "Sign in to a server first."))

        // Written before the cancellation, so the worker's failure path cannot overwrite it. See above.
        val paused = downloads.markPaused(profile.serverId, bookId)
        if (paused is AppResult.Failure) return paused

        scheduler.cancel(profile.serverId, bookId)
        return AppResult.Success(Unit)
    }
}
