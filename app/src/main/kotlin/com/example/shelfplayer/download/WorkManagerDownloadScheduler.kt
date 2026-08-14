package com.example.shelfplayer.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.domain.download.DownloadScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC DL-001 / DL-004 / §12 — [DownloadScheduler] against WorkManager.
 *
 * ### Connected, not unmetered — for now
 *
 * The constraint here is `CONNECTED`, which looks wrong against DL-004's "manual downloads: Wi-Fi only by
 * default". It is deliberate and temporary: the network **policy** is slice 5, and putting an unmetered
 * constraint here first would mean a download that silently never starts on a phone with no Wi-Fi, with
 * nothing in the UI able to say why. A constraint the user cannot see or change is worse than no constraint.
 *
 * When slice 5 lands, the policy decides this value and the UI explains it. Until then the app does what the
 * user asked, immediately, which is the honest behaviour for a button that was just pressed.
 *
 * ### Expedited, with a fallback
 *
 * `setExpedited` asks Android to start now rather than at its convenience. A download the user just pressed
 * is exactly the case expedited work is for, and the quota fallback is a plain job rather than a refusal —
 * running late is better than not running.
 *
 * ### `KEEP`, not `REPLACE`
 *
 * A second tap on a book already downloading must not restart it. `KEEP` makes the second tap a no-op, which
 * is also what makes the use case's "record the claim, then enqueue" safe to call from a second profile.
 */
@Singleton
class WorkManagerDownloadScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val logger: Logger,
) : DownloadScheduler {

    override suspend fun enqueue(serverId: ServerId, itemId: LibraryItemId) {
        val request = OneTimeWorkRequestBuilder<BookDownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(BookDownloadWorker.KEY_SERVER_ID, serverId.value)
                    .putString(BookDownloadWorker.KEY_ITEM_ID, itemId.value)
                    .build(),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    // Not `setRequiresStorageNotLow`: the use case already checked free space against this
                    // book's own size, which is a better question than Android's device-wide threshold, and
                    // a job blocked by the system one would wait with no way to explain itself.
                    .build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            BookDownloadWorker.nameFor(serverId, itemId),
            ExistingWorkPolicy.KEEP,
            request,
        )
        logger.info(LogCategory.Sync, "A book download was queued")
    }

    override suspend fun cancel(serverId: ServerId, itemId: LibraryItemId) {
        WorkManager.getInstance(context).cancelUniqueWork(BookDownloadWorker.nameFor(serverId, itemId))
        logger.info(LogCategory.Sync, "A book download was cancelled")
    }

    private companion object {
        /**
         * Thirty seconds, doubling. WorkManager's own floor is ten and its default is thirty.
         *
         * A download fails for one of two reasons — the connection went, or the server did — and both are
         * usually over in under a minute. Starting the backoff there means a passing tunnel costs one retry
         * rather than a quarter of an hour of waiting.
         */
        const val BACKOFF_SECONDS = 30L
    }
}
