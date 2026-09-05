package com.example.shelfplayer.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.shelfplayer.R
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.data.downloads.BookDownloader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    private var fraction = 0f

    override suspend fun doWork(): Result = coroutineScope {
        val profileId = inputData.getString(KEY_PROFILE_ID)?.let(::ProfileId)
            ?: return@coroutineScope Result.success()
        val serverId = inputData.getString(KEY_SERVER_ID)?.let(::ServerId)
            ?: return@coroutineScope Result.success()
        val itemId = inputData.getString(KEY_ITEM_ID)?.let(::LibraryItemId)
            ?: return@coroutineScope Result.success()

        logger.info(LogCategory.Sync, "A book download started")
        setForeground(foregroundInfo(progress = 0f))
        val ticker = launch { publishWhileRunning() }

        val outcome = downloader.download(profileId, serverId, itemId) { progress -> fraction = progress }
        ticker.cancel()

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

    /** Redraws the notification at most once per second rather than once per copied buffer. */
    private suspend fun publishWhileRunning() {
        var lastPublished = -1
        while (true) {
            delay(PUBLISH_INTERVAL_MILLIS)
            val percent = (fraction * PERCENT).roundToInt().coerceIn(0, PERCENT)
            if (percent == lastPublished) continue
            lastPublished = percent
            setForeground(foregroundInfo(fraction))
        }
    }

    private fun foregroundInfo(progress: Float): ForegroundInfo {
        ensureChannel()
        val percent = (progress * PERCENT).roundToInt().coerceIn(0, PERCENT)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.download_notification_title))
            .setContentText(appContext.getString(R.string.download_notification_progress, percent))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(PERCENT, percent, false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
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

        /** One physical job per shared downloaded copy. */
        fun nameFor(serverId: ServerId, itemId: LibraryItemId): String = "download:${serverId.value}:${itemId.value}"

        private const val CHANNEL_ID = "shelfplayer.downloads"
        private const val NOTIFICATION_ID = 4_201
        private const val PERCENT = 100
        private const val PUBLISH_INTERVAL_MILLIS = 1_000L
    }
}
