package com.example.shelfplayer.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.usecase.SyncAccountUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * PRODUCT_SPEC SYNC-003 — the periodic refresh, for the days the app is not opened.
 *
 * A failed run retries immediately through WorkManager only when the application's own error taxonomy says
 * the same operation can plausibly succeed unchanged. Authentication, authorization, validation and API
 * compatibility failures need external intervention; putting those into exponential backoff just repeats a
 * request whose answer cannot change.
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

        when (val account = syncAccount(profileId)) {
            is AppResult.Failure -> return resultFor(account.error)
            is AppResult.Success -> Unit
        }

        return when (val library = libraryRepository.refresh(profileId)) {
            is AppResult.Success -> Result.success()
            is AppResult.Failure -> resultFor(library.error)
        }
    }

    private fun resultFor(error: AppError): Result {
        val retry = shouldRetryBackgroundSync(error)
        logger.info(
            LogCategory.Sync,
            "Background refresh stopped after a typed failure",
            LogField.Public("error", error.code),
            LogField.Public("retry", retry),
        )
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_PROFILE_ID = "profileId"

        fun nameFor(profileId: ProfileId): String = "library-sync-${profileId.value}"
    }
}

/**
 * PRODUCT_SPEC 14.3 — WorkManager does not invent a second retry taxonomy.
 *
 * Kept as a pure internal function so the complete policy matrix can be tested without constructing an
 * Android Worker. `AppError.isRetryable` remains the source of truth.
 */
internal fun shouldRetryBackgroundSync(error: AppError): Boolean = error.isRetryable
