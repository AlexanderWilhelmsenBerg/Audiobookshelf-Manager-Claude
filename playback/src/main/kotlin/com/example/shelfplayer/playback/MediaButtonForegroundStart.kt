package com.example.shelfplayer.playback

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.warn

/** Matches the initial Play-capable keys that Media3's receiver starts in the foreground on API 26+. */
internal fun KeyEvent.canStartMediaButtonReceiver(): Boolean = action == KeyEvent.ACTION_DOWN && repeatCount == 0 &&
    (
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            keyCode == KeyEvent.KEYCODE_HEADSETHOOK
        )

/**
 * ROUTE-001 / #295. Answer an empty-queue receiver start before asynchronous playback resumption.
 * Media3 uses the same ID when it replaces the deferred notification for actual playback.
 */
@OptIn(UnstableApi::class)
internal fun startMediaButtonForeground(service: Service, logger: Logger): Boolean {
    val channel = DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID
    val manager = requireNotNull(service.getSystemService(NotificationManager::class.java))
    manager.createNotificationChannel(
        NotificationChannel(
            channel,
            service.getText(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_NAME_RESOURCE_ID),
            NotificationManager.IMPORTANCE_LOW,
        ),
    )
    val notification = NotificationCompat.Builder(service, channel)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setOnlyAlertOnce(true)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
        .build()
    val promote = {
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(
            service,
            DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
            notification,
            foregroundType,
        )
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            promote()
        } catch (_: ForegroundServiceStartNotAllowedException) {
            logger.warn(LogCategory.Playback, "Android denied the media button foreground start")
            return false
        }
    } else {
        promote()
    }
    return true
}
