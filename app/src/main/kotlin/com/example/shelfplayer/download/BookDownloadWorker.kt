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
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.data.downloads.BookDownloader
import com.example.shelfplayer.domain.repository.ProfileRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * PRODUCT_SPEC DL-001 / §12 — one book's transfer, as work that survives the screen.
 *
 * ### Foreground, with a progress notification
 *
 * *"Download continues in background with a progress notification."* A `setForeground` worker is what keeps
 * a multi-hundred-megabyte transfer alive when the user leaves the app, and the notification is not
 * decoration — it is the thing Android requires in exchange, and the only way a user can tell that the
 * download they started is still happening.
 *
 * The notification names **no book**. PRODUCT_SPEC 14.5 keeps private self-hosted data out of anything that
 * leaves the app's own surface, and a notification is read on a lock screen, mirrored to a watch, and read
 * aloud by a car. "Downloading a book — 42%" is as much as it says.
 *
 * ### `retry`, not `failure`
 *
 * The same rule `LibrarySyncWorker` follows: a transfer that could not reach the server is the ordinary
 * state of a phone. `Result.retry()` hands it to WorkManager's exponential backoff, which already respects
 * the battery and network constraints the request was built with. Reimplementing that here would mean
 * holding a wake lock through our own sleep.
 *
 * The one outcome that is `failure` rather than `retry` is a refusal that will not change by trying again —
 * a revoked permission, a book that is gone. Those are recorded on the manifest with a summary the storage
 * screen can show, so a silent `failure` still leaves an explanation behind.
 *
 * ### Cancellation
 *
 * Stopping the worker cancels the coroutine, which `AbsDownloadApi`'s copy loop checks between buffers. The
 * partial file is left on disk deliberately: it is what the next attempt resumes from, and a user who
 * cancelled on a train has not asked to throw away the eighty per cent they already have.
 */
@HiltWorker
class BookDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val profiles: ProfileRepository,
    private val downloader: BookDownloader,
    private val logger: Logger,
) : CoroutineWorker(appContext, params) {

    /**
     * The most recent fraction the transfer reported, read by the notification ticker.
     *
     * `@Volatile` because the write happens on whichever IO thread is copying bytes and the read on the
     * coroutine driving the notification. A torn `Float` would only ever draw a wrong percentage for one
     * frame, but a value that never becomes visible would freeze the bar — which is the failure this
     * annotation actually prevents.
     */
    @Volatile
    private var fraction = 0f

    override suspend fun doWork(): Result = coroutineScope {
        val serverId = inputData.getString(KEY_SERVER_ID)?.let(::ServerId) ?: return@coroutineScope Result.success()
        val itemId = inputData.getString(KEY_ITEM_ID)?.let(::LibraryItemId) ?: return@coroutineScope Result.success()
        val profileId = profiles.observeActiveProfile().first()?.id ?: return@coroutineScope Result.success()

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

    /**
     * Redraws the notification on a timer rather than on every byte.
     *
     * The transfer's own callback fires once per 64 KiB buffer — thousands of times per file, on the thread
     * doing the copying — and `setForeground` is a binder call. Driving it from there would make the
     * notification a measurable share of what a download costs, and the callback cannot suspend anyway.
     *
     * A second is faster than a person can read a changing number and slow enough to be free. The loop ends
     * when the transfer does, because its scope is cancelled.
     */
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
            // A lock screen is somebody else's view of the phone. The text names no book, but the category
            // and visibility are set explicitly rather than left to a default that may change.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Created on demand rather than at start-up.
     *
     * A channel that exists before anything uses it shows up in the system settings as a switch for a
     * feature the user has never touched. This one appears the first time a download does.
     */
    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.download_notification_channel),
            // Low: a progress bar is information, not an interruption. No sound, no heads-up.
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        NotificationManagerCompat.from(appContext).createNotificationChannel(channel)
    }

    companion object {
        const val KEY_SERVER_ID: String = "serverId"
        const val KEY_ITEM_ID: String = "itemId"

        /**
         * PRODUCT_SPEC §12 — one job per book, so cancel and retry can find it.
         *
         * The item id alone would collide across servers, which is not hypothetical on a device signed in to
         * a home server and a friend's.
         */
        fun nameFor(serverId: ServerId, itemId: LibraryItemId): String = "download:${serverId.value}:${itemId.value}"

        private const val CHANNEL_ID = "shelfplayer.downloads"
        private const val NOTIFICATION_ID = 4_201
        private const val PERCENT = 100
        private const val PUBLISH_INTERVAL_MILLIS = 1_000L
    }
}
