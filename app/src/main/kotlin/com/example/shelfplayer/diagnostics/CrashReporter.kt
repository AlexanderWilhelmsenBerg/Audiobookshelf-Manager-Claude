package com.example.shelfplayer.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Process
import androidx.annotation.RequiresApi
import com.example.shelfplayer.BuildConfig
import com.example.shelfplayer.core.common.log.EventLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Installs BookWave's local crash capture without replacing Android's fatal handler.
 *
 * The handler writes a small sanitized envelope first and always delegates to the handler Android installed
 * before us. It never converts a crash into a recoverable state. On API 30+, [capturePreviousExit] also
 * checks Android's historical process-exit record so ANRs/native crashes can be reported even though no
 * Java uncaught-exception callback ran.
 */
@Singleton
class CrashReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reports: CrashReportStore,
    private val events: EventLog,
) {
    private val installed = AtomicBoolean(false)

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                reports.recordUncaught(
                    at = Instant.now(),
                    app = appLabel(),
                    sdk = Build.VERSION.SDK_INT,
                    mainThread = Looper.getMainLooper().thread === thread,
                    throwable = throwable,
                    events = events.events.value,
                )
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    Process.killProcess(Process.myPid())
                    exitProcess(FALLBACK_EXIT_CODE)
                }
            }
        }
    }

    /** Run off the main thread after startup; it may make a small system-service call and one tiny file write. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun capturePreviousExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            capturePreviousExitOnR()
        } catch (_: Exception) {
            // Diagnostics are best-effort. A broken diagnostics path must never become an app-start failure.
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun capturePreviousExitOnR() {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val latest = activityManager
            .getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_RECORDS)
            .maxByOrNull { info -> info.timestamp }
            ?: return
        if (latest.timestamp <= reports.handledExitTimestamp()) return

        if (latest.reason in reportableReasons()) {
            val existing = reports.latestMeta()
            val detailedHandlerReportAlreadyExists = existing?.source == CrashReportSource.UncaughtException &&
                abs(existing.occurredAtEpochMs - latest.timestamp) <= SAME_EXIT_WINDOW_MS
            if (!detailedHandlerReportAlreadyExists) {
                reports.recordProcessExit(
                    at = Instant.ofEpochMilli(latest.timestamp),
                    app = appLabel(),
                    sdk = Build.VERSION.SDK_INT,
                    reason = exitReasonName(latest.reason),
                    reasonCode = latest.reason,
                    status = latest.status,
                    importance = latest.importance,
                )
            }
        }

        // Mark every previous process once, including normal exits. Otherwise clearing a crash report would
        // cause the same historical Android record to be rediscovered and recreated on the next launch.
        reports.markExitHandled(latest.timestamp)
    }

    private fun appLabel(): String =
        "${BuildConfig.VERSION_NAME} ${BuildConfig.BUILD_TYPE} ${BuildConfig.GIT_COMMIT} ${BuildConfig.GIT_BRANCH}"

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reportableReasons(): Set<Int> = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    )

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        else -> "REASON_$reason"
    }

    private companion object {
        const val MAX_EXIT_RECORDS = 8
        const val SAME_EXIT_WINDOW_MS = 120_000L
        const val FALLBACK_EXIT_CODE = 10
    }
}
