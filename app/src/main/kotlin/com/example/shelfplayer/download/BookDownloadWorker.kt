package com.example.shelfplayer.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.shelfplayer.MainActivity
import com.example.shelfplayer.R
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.download.DownloadProgress
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.data.downloads.BookDownloader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PRODUCT_SPEC DL-001 / §12 — one book's transfer, as work that survives the screen.
 *
 * The profile that authorized the transfer is part of [inputData]. A worker can start after a process
 * restart or profile switch, so reading the currently active profile here would let mutable UI state change
 * the credentials of already queued work.
 */
@HiltWorker
class BookDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloader: BookDownloader,
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

    private fun foregroundInfo(
        snapshot: DownloadProgress,
        serverId: ServerId,
        itemId: LibraryItemId,
    ): ForegroundInfo {
        ensureChannel()
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.download_notification_title))
            .setContentText(progressText(snapshot))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(downloadsPendingIntent(serverId, itemId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(PERCENT, snapshot.percent, false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(NOTIFICATION_GROUP)
            .build()
        val notificationId = notificationIdFor(serverId, itemId)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun progressText(snapshot: DownloadProgress): String {
        val percentage = appContext.getString(R.string.download_notification_progress, snapshot.percent)
        val total = snapshot.totalBytes ?: return percentage
        val downloaded = Formatter.formatShortFileSize(appContext, snapshot.downloadedBytes)
        val totalText = Formatter.formatShortFileSize(appContext, total)
        return appContext.getString(R.string.download_notification_progress_bytes, percentage, downloaded, totalText)
    }

    private fun downloadsPendingIntent(serverId: ServerId, itemId: LibraryItemId): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            appContext,
            notificationIdFor(serverId, itemId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.download_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        NotificationManagerCompat.from(appContext).createNotificationChannel(channel)
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

        private const val CHANNEL_ID = "shelfplayer.downloads"
        private const val NOTIFICATION_GROUP = "bookwave.downloads"
        private const val NOTIFICATION_ID_BASE = 4_200
        private const val PERCENT = 100
        private const val PUBLISH_INTERVAL_MILLIS = 1_000L

        internal fun notificationIdFor(serverId: ServerId, itemId: LibraryItemId): Int =
            NOTIFICATION_ID_BASE + (nameFor(serverId, itemId).hashCode() and 0x3fff_ffff)

        internal fun progressData(progress: DownloadProgress) = workDataOf(
            KEY_PROGRESS_BYTES to progress.downloadedBytes,
            KEY_PROGRESS_TOTAL_BYTES to (progress.totalBytes ?: -1L),
            KEY_PROGRESS_PERCENT to progress.percent,
        )
    }
}
