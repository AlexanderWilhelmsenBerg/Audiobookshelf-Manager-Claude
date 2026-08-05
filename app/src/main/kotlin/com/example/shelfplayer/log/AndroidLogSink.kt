package com.example.shelfplayer.log

import android.util.Log
import com.example.shelfplayer.core.common.log.LogLevel
import com.example.shelfplayer.core.common.log.LogSink
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC 14.5 — the one place in the application that touches `android.util.Log`.
 *
 * By the time a line reaches here it has already been through the redactor, so this class cannot
 * leak anything: it has no access to the original [com.example.shelfplayer.core.common.log.LogEvent]
 * fields, only to the rendered string.
 *
 * The tag is prefixed so a `logcat -s ShelfPlayer:*` filter picks up every category.
 */
@Singleton
class AndroidLogSink @Inject constructor() : LogSink {
    override fun write(level: LogLevel, tag: String, line: String) {
        val fullTag = "$TAG_PREFIX/$tag"
        when (level) {
            LogLevel.Verbose -> Log.v(fullTag, line)
            LogLevel.Debug -> Log.d(fullTag, line)
            LogLevel.Info -> Log.i(fullTag, line)
            LogLevel.Warn -> Log.w(fullTag, line)
            LogLevel.Error -> Log.e(fullTag, line)
        }
    }

    private companion object {
        const val TAG_PREFIX = "ShelfPlayer"
    }
}
