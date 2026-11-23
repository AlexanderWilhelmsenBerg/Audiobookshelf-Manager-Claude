package com.example.shelfplayer.playback

import android.content.Context
import android.os.Build
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.time.AppClock

internal const val EARLY_HEADSET_PREFERENCE_REFRESH = true

/** Debug builds run the automatic take-back experiment where the platform router exposes system routes. */
internal fun createHeadsetTakeBack(
    context: Context,
    clock: AppClock,
    log: (String, List<LogField>) -> Unit,
): HeadsetTakeBack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    SystemRouteHeadsetTakeBack(
        PlatformSystemRoutePort(context),
        clock,
        enabled = { AutomaticHeadsetTakeBackSetting.enabled(context) },
        log = log,
    )
} else {
    NoHeadsetTakeBack
}
