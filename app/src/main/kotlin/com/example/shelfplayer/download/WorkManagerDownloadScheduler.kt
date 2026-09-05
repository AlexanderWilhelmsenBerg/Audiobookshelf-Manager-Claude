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
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.NetworkPolicy
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
 * WorkManager separates enqueue time from execution time, so the request stores all three facts needed to
 * perform the transfer later: the profile that authorized it, the server, and the item. The work name stays
 * per (server, item), because multiple profile claims intentionally share one physical downloaded copy.
 */
@Singleton
class WorkManagerDownloadScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: PlaybackSettingsRepository,
    private val logger: Logger,
) : DownloadScheduler {

    override suspend fun enqueue(
        profileId: ProfileId,
        serverId: ServerId,
        itemId: LibraryItemId,
        category: TrafficCategory,
    ) {
        val policy = settings.observeNetworkPolicy().first()
        val network = networkFor(policy, category)
        val request = OneTimeWorkRequestBuilder<BookDownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(BookDownloadWorker.KEY_PROFILE_ID, profileId.value)
                    .putString(BookDownloadWorker.KEY_SERVER_ID, serverId.value)
                    .putString(BookDownloadWorker.KEY_ITEM_ID, itemId.value)
                    .build(),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(network)
                    .build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            BookDownloadWorker.nameFor(serverId, itemId),
            existingWorkPolicyFor(policy, category),
            request,
        )
        logger.info(
            LogCategory.Sync,
            "A book download was queued",
            LogField.Public("network", network.name),
            LogField.Public("category", category.name),
        )
    }

    override suspend fun cancel(serverId: ServerId, itemId: LibraryItemId) {
        WorkManager.getInstance(context).cancelUniqueWork(BookDownloadWorker.nameFor(serverId, itemId))
        logger.info(LogCategory.Sync, "A book download was cancelled")
    }

    private companion object {
        /** Thirty seconds, doubling. WorkManager's own floor is ten and its default is thirty. */
        const val BACKOFF_SECONDS = 30L
    }
}

/** PRODUCT_SPEC DL-004 — cellular for this kind of traffic, or Wi-Fi only. */
internal fun networkFor(policy: NetworkPolicy, category: TrafficCategory): NetworkType =
    if (policy.allowsCellular(category)) NetworkType.CONNECTED else NetworkType.UNMETERED

/**
 * Whether this request may replace work already queued for the same shared book copy.
 *
 * Only a manual request may relax a stricter automatic network constraint. Profile identity is job data,
 * not part of the unique work name: replacing a queued request deliberately replaces the credential owner
 * at the same moment the newer user action replaces the network policy.
 */
internal fun existingWorkPolicyFor(policy: NetworkPolicy, category: TrafficCategory): ExistingWorkPolicy {
    val mayRelax = category == TrafficCategory.ManualDownload &&
        policy.allowsCellular(TrafficCategory.ManualDownload) &&
        !policy.allowsCellular(TrafficCategory.SmartDownload)
    return if (mayRelax) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
}
