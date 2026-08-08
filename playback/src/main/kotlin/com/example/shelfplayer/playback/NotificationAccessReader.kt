package com.example.shelfplayer.playback

import android.app.NotificationManager
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import com.example.shelfplayer.core.model.playback.NotificationAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC PLAY-001 — reads whether the media notification can appear, and whether it has.
 *
 * ### Why this exists
 *
 * A device test reported that a playing book showed no notification. From inside the app that has three
 * possible causes and they are indistinguishable without asking the platform: the runtime permission was
 * declined (Android 13+), the media channel was silenced, or the notification was never posted. The first two
 * are the user's settings and the third is our defect, and guessing between them costs a round trip per
 * guess.
 *
 * [read] answers all three. It is a point-in-time read rather than a flow: notification state has no change
 * callback, and the screen that shows it already recomposes often enough to be current.
 *
 * ### Why it lives in `:playback`
 *
 * The channel it asks about is Media3's, named by `DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID`.
 * `:playback` is the only module allowed to name Media3 (PRODUCT_SPEC 9.2), so the constant cannot be read
 * anywhere else without leaking that dependency.
 */
fun interface NotificationAccessReader {
    fun read(): NotificationAccess
}

/**
 * The real reader, against the platform's notification manager.
 *
 * Behind an interface for the reason [PlayerFactory] is: it is the one thing on the settings screen that
 * cannot be answered without an Android runtime, and a plain JVM test of the ViewModel should not have to
 * bring Robolectric in to construct one.
 */
@Singleton
internal class DefaultNotificationAccessReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : NotificationAccessReader {

    @OptIn(UnstableApi::class)
    override fun read(): NotificationAccess {
        val compat = NotificationManagerCompat.from(context)
        val channel = compat.getNotificationChannelCompat(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID)
        return NotificationAccess(
            // `true` below API 33, where the permission does not exist and this reports the user's
            // notification setting for the app.
            isAllowed = compat.areNotificationsEnabled(),
            // `null` means Media3 has not created the channel yet, which is not the same as blocked: nothing
            // has played. Only an existing channel at `IMPORTANCE_NONE` is a channel the user turned off.
            isChannelBlocked = channel != null && channel.importance == NotificationManagerCompat.IMPORTANCE_NONE,
            isShowing = isMediaNotificationPosted(),
        )
    }

    /**
     * Whether Media3's notification is posted right now.
     *
     * `getActiveNotifications` returns only *this* app's notifications, so it needs no permission and reveals
     * nothing about other apps. It is matched on Media3's own id rather than on "any notification", because
     * the app could be showing something else entirely and that would not satisfy PLAY-001.
     */
    @OptIn(UnstableApi::class)
    private fun isMediaNotificationPosted(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.activeNotifications.any { it.id == DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID }
    }
}
