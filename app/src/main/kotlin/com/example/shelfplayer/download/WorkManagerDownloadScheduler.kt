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
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.TrafficCategory
import com.example.shelfplayer.domain.download.DownloadScheduler
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC DL-001 / DL-004 / §12 — [DownloadScheduler] against WorkManager.
 *
 * ### The network constraint is the user's setting, not a constant
 *
 * DL-004's default is Wi-Fi only for manual downloads, so the constraint is `UNMETERED` unless the user has
 * turned cellular on for downloads in Settings (ADR-0018 decision 5). WorkManager then holds the job until
 * the network qualifies, which is exactly the requirement's "switching from Wi-Fi to cellular during a
 * disallowed download pauses it" — the platform pauses and resumes it for us, without a wake lock or a
 * poll of our own.
 *
 * A constraint the user cannot see or change would be worse than none, because a download that silently
 * never starts is indistinguishable from a broken button. That is why this landed with the setting rather
 * than before it, and why the book screen reports *waiting for Wi-Fi* rather than showing an idle ring.
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
    private val settings: PlaybackSettingsRepository,
    private val logger: Logger,
) : DownloadScheduler {

    override suspend fun enqueue(serverId: ServerId, itemId: LibraryItemId) {
        val policy = settings.observeNetworkPolicy().first()
        val network = if (policy.allowsCellular(TrafficCategory.ManualDownload)) {
            NetworkType.CONNECTED
        } else {
            NetworkType.UNMETERED
        }
        val request = OneTimeWorkRequestBuilder<BookDownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(BookDownloadWorker.KEY_SERVER_ID, serverId.value)
                    .putString(BookDownloadWorker.KEY_ITEM_ID, itemId.value)
                    .build(),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(network)
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
        logger.info(
            LogCategory.Sync,
            "A book download was queued",
            LogField.Public("network", network.name),
        )
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
