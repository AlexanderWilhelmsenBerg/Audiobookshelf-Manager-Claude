package com.example.shelfplayer.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadProgress
import com.example.shelfplayer.data.downloads.BookDownloader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PRODUCT_SPEC DL-001 / §12 — one book's transfer, as work that survives the screen.
 *
 * The profile that authorized the transfer is part of [inputData], as a preferred credential owner. A worker
 * can start after a process restart or profile switch, so it never reads the currently active profile.
 * BookDownloader rechecks the shared copy's current entitled claimants at each file/artwork request: if the
 * original owner leaves, a remaining claimant can continue without changing this job's network constraints.
 */
@HiltWorker
class BookDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloader: BookDownloader,
    private val notifications: DownloadNotificationFactory,
    private val logger: Logger,
) : CoroutineWorker(appContext, params) {

    @Volatile
    private var progress = DownloadProgress(downloadedBytes = 0L, totalBytes = null, fraction = 0f)

    override suspend fun doWork(): Result = coroutineScope {
        val profileId = inputData.getString(KEY_PROFILE_ID)?.let(::ProfileId)
            ?: return@coroutineScope Result.success()
        val serverId = inputData.getString(KEY_SERVER_ID)?.let(::ServerId)
            ?: return@coroutineScope Result.success()
        val itemId = inputData.getString(KEY_ITEM_ID)?.let(::LibraryItemId)
            ?: return@coroutineScope Result.success()

        logger.info(LogCategory.Sync, "A book download started")
        publish(progress, serverId, itemId)
        val ticker = launch { publishWhileRunning(serverId, itemId) }

        val outcome = downloader.download(profileId, serverId, itemId) { snapshot -> progress = snapshot }
        ticker.cancel()
        setProgress(progressData(progress))

        when (outcome) {
            is AppResult.Success -> {
                logger.info(
                    LogCategory.Sync,
                    "A book download finished",
                    LogField.Count("files", outcome.value.files.size),
                )
                Result.success()
            }

            is AppResult.Failure -> if (outcome.error.isRetryable) Result.retry() else Result.failure()
        }
    }

    /** Publishes WorkManager progress and redraws this book's notification at most once per second. */
    private suspend fun publishWhileRunning(serverId: ServerId, itemId: LibraryItemId) {
        var lastPublished: DownloadProgress? = null
        while (true) {
            delay(PUBLISH_INTERVAL_MILLIS)
            val current = progress
            if (current == lastPublished) continue
            lastPublished = current
            publish(current, serverId, itemId)
        }
    }

    private suspend fun publish(snapshot: DownloadProgress, serverId: ServerId, itemId: LibraryItemId) {
        setProgress(progressData(snapshot))
        setForeground(foregroundInfo(snapshot, serverId, itemId))
    }

    private fun foregroundInfo(snapshot: DownloadProgress, serverId: ServerId, itemId: LibraryItemId): ForegroundInfo {
        val notification = notifications.notification(
            serverId = serverId,
            itemId = itemId,
            state = com.example.shelfplayer.domain.download.DownloadRecoveryState.Running,
            progress = snapshot,
        )
        val notificationId = notifications.notificationId(serverId, itemId)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val KEY_PROFILE_ID: String = "profileId"
        const val KEY_SERVER_ID: String = "serverId"
        const val KEY_ITEM_ID: String = "itemId"
        const val KEY_PROGRESS_BYTES: String = "downloadedBytes"
        const val KEY_PROGRESS_TOTAL_BYTES: String = "totalBytes"
        const val KEY_PROGRESS_PERCENT: String = "percent"

        /** Aggregate observation tag. It carries no profile, server or item identity. */
        const val DOWNLOAD_TAG: String = "bookwave:download"

        /** One physical job per shared downloaded copy. */
        fun nameFor(serverId: ServerId, itemId: LibraryItemId): String = "download:${serverId.value}:${itemId.value}"

        private const val PUBLISH_INTERVAL_MILLIS = 1_000L

        internal fun progressData(progress: DownloadProgress) = workDataOf(
            KEY_PROGRESS_BYTES to progress.downloadedBytes,
            KEY_PROGRESS_TOTAL_BYTES to (progress.totalBytes ?: -1L),
            KEY_PROGRESS_PERCENT to progress.percent,
        )
    }
}
