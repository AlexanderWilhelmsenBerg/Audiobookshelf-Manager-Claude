package com.example.shelfplayer.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.shelfplayer.MainActivity
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadProgress
import com.example.shelfplayer.domain.download.DownloadRecoveryState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #29 — one privacy-safe notification renderer for worker foreground execution and process-level pending
 * state. It never accepts media metadata or profile identity, so a lock-screen notification cannot
 * accidentally reveal a title, author, profile, path, server ID or item ID in visible copy.
 */
@Singleton
class DownloadNotificationFactory @Inject constructor(@param:ApplicationContext private val context: Context) {
    fun notification(
        serverId: ServerId,
        itemId: LibraryItemId,
        state: DownloadRecoveryState,
        progress: DownloadProgress?,
    ): android.app.Notification {
        ensureChannel()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.download_notification_title))
            .setContentText(contentText(state, progress))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(downloadsPendingIntent(serverId, itemId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(NOTIFICATION_GROUP)

        when {
            progress != null -> builder.setProgress(PERCENT, progress.percent, false)
            state == DownloadRecoveryState.Running -> builder.setProgress(0, 0, true)
            else -> builder.setProgress(0, 0, false)
        }
        return builder.build()
    }

    fun notificationId(serverId: ServerId, itemId: LibraryItemId): Int =
        NOTIFICATION_ID_BASE + (BookDownloadWorker.nameFor(serverId, itemId).hashCode() and 0x3fff_ffff)

    fun cancel(serverId: ServerId, itemId: LibraryItemId) {
        NotificationManagerCompat.from(context).cancel(notificationId(serverId, itemId))
    }

    fun post(serverId: ServerId, itemId: LibraryItemId, state: DownloadRecoveryState, progress: DownloadProgress?) {
        NotificationManagerCompat.from(context).notify(
            notificationId(serverId, itemId),
            notification(serverId, itemId, state, progress),
        )
    }

    private fun contentText(state: DownloadRecoveryState, progress: DownloadProgress?): String {
        val status = when (state) {
            DownloadRecoveryState.Queued -> context.getString(R.string.downloads_queued)
            DownloadRecoveryState.Waiting -> context.getString(R.string.downloads_waiting)
            DownloadRecoveryState.Running -> context.getString(R.string.downloads_downloading)
            DownloadRecoveryState.Retrying -> context.getString(R.string.downloads_retrying)
            DownloadRecoveryState.Paused -> context.getString(R.string.downloads_paused)
            DownloadRecoveryState.Failed -> context.getString(R.string.downloads_failed)
            DownloadRecoveryState.Complete -> context.getString(R.string.book_downloaded)
        }
        val progressText = progress?.let(::progressText) ?: return status
        return "$status · $progressText"
    }

    private fun progressText(progress: DownloadProgress): String {
        val percentage = context.getString(R.string.download_notification_progress, progress.percent)
        val total = progress.totalBytes ?: return percentage
        val downloaded = Formatter.formatShortFileSize(context, progress.downloadedBytes)
        val totalText = Formatter.formatShortFileSize(context, total)
        return context.getString(R.string.download_notification_progress_bytes, percentage, downloaded, totalText)
    }

    private fun downloadsPendingIntent(serverId: ServerId, itemId: LibraryItemId): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            notificationId(serverId, itemId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.download_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "shelfplayer.downloads"
        private const val NOTIFICATION_GROUP = "bookwave.downloads"
        private const val NOTIFICATION_ID_BASE = 4_200
        private const val PERCENT = 100
    }
}
