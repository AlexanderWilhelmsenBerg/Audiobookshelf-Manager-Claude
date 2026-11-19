package com.example.shelfplayer.playback

import android.content.Context
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.time.AppClock

/** Release builds carry no system-route adapter and do not declare the permission it needs. */
@Suppress("UNUSED_PARAMETER")
internal fun createHeadsetTakeBack(
    context: Context,
    clock: AppClock,
    log: (String, List<LogField>) -> Unit,
): HeadsetTakeBack = NoHeadsetTakeBack
