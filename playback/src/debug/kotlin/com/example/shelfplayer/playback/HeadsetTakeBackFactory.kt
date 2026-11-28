package com.example.shelfplayer.playback

import android.content.Context
import android.os.Build
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.time.AppClock

/** Debug builds run the automatic take-back experiment where the platform router exposes system routes. */
internal fun createHeadsetTakeBack(
    context: Context,
    clock: AppClock,
    trials: HeadsetOutputTrials,
    log: (String, List<LogField>) -> Unit,
): HeadsetTakeBack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    SystemRouteHeadsetTakeBack(
        PlatformSystemRoutePort(context),
        clock,
        enabled = { trials.active.value.takeBackOverride ?: AutomaticHeadsetTakeBackSetting.enabled(context) },
        earlyEnabled = { trials.active.value.earlyTakeBack },
        log = log,
    )
} else {
    NoHeadsetTakeBack
}
