package com.example.shelfplayer.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.usecase.SyncAccountUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * PRODUCT_SPEC SYNC-003 — the periodic refresh, for the days the app is not opened.
 *
 * ### What it does, and the order it does it in
 *
 * The account first, then the library. The account sync is one request and brings back positions,
 * permissions and whether the account still works; the library sweep is an N+1 over every item. If the
 * run is cut short — and a periodic worker frequently is — the cheap, high-value half has already
 * happened.
 *
 * ### Failure is `retry`, not `failure`
 *
 * A refresh that could not reach the server is the ordinary state of a phone, not a defect in the work.
 * `Result.retry()` hands it back to WorkManager's own backoff, which already respects the battery and
 * network policy PRODUCT_SPEC SYNC-003 asks for — reimplementing that here would mean holding a wake
 * lock through our own delay.
 *
 * A profile that no longer exists is `success`, because the work has nothing left to do and retrying
 * would never change that. Its schedule is cancelled on removal, so this is only reachable in the race
 * between a removal and a run already in flight.
 */
@HiltWorker
class LibrarySyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncAccount: SyncAccountUseCase,
    private val libraryRepository: LibraryRepository,
    private val logger: Logger,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val profileId = inputData.getString(KEY_PROFILE_ID)?.let(::ProfileId) ?: return Result.success()

        logger.info(
            LogCategory.Sync,
            "Background refresh started",
            LogField.Identifier("profile", profileId.value),
        )

        val account = syncAccount(profileId)
        if (account is AppResult.Failure) return Result.retry()

        // PRODUCT_SPEC SYNC-003 — "background work never wakes the device solely to refresh cover art".
        // It does not, because nothing here fetches one: covers are loaded by the image pipeline when a
        // screen asks for them, and this worker draws no images.
        return when (libraryRepository.refresh(profileId)) {
            is AppResult.Success -> Result.success()
            is AppResult.Failure -> Result.retry()
        }
    }

    companion object {
        const val KEY_PROFILE_ID = "profileId"

        /**
         * The unique work name PRODUCT_SPEC SYNC-003 requires per profile.
         *
         * The profile id is already a hash of the server and account, so this name reveals nothing
         * about either — which matters because WorkManager's own diagnostics print it.
         */
        fun nameFor(profileId: ProfileId): String = "library-sync-${profileId.value}"
    }
}
