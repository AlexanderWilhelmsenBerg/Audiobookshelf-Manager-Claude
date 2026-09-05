package com.example.shelfplayer.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.example.shelfplayer.core.common.log.EventLog
import com.example.shelfplayer.core.common.log.LogLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Temporary PR #83 device-test aid.
 *
 * [EventLog] is intentionally in memory, so the useful seconds before a process crash disappear with the
 * process. While #83 is under device test, keep one private crash snapshot containing only data that is
 * already redacted plus exception/stack-frame class names. It is replayed into Event Log on the next launch
 * and immediately deleted. Remove this helper before #83 is merged once the crash has been diagnosed.
 *
 * Android 11+ also records why the previous process exited. That survives exits which never reach an
 * uncaught-exception handler, such as native crashes, ANRs and system kills. Only numeric/process-state
 * metadata is copied into Event Log; the platform description and trace are deliberately not read because
 * they can contain application or server data.
 *
 * Exception messages are never persisted: network and file exceptions routinely contain hosts and paths.
 */
@Singleton
class CrashSnapshotStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val eventLog: EventLog,
) {
    private val snapshotFile: File get() = File(context.filesDir, FILE_NAME)

    /** Install before application coroutines start so their uncaught failures are captured too. */
    fun install() {
        val delegate = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { capture(throwable) }
            delegate?.uncaughtException(thread, throwable)
        }
    }

    /** Restore any diagnostic evidence Android or the previous process left for this launch. */
    fun restore() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) reportPreviousProcessExit()

        val lines = runCatching {
            if (snapshotFile.isFile) snapshotFile.readLines().take(MAX_RESTORED_LINES) else emptyList()
        }.getOrDefault(emptyList())
        if (lines.isEmpty()) return

        eventLog.write(LogLevel.Error, CRASH_TAG, "Recovered diagnostics from the previous process crash")
        lines.forEach { line -> eventLog.write(LogLevel.Debug, CRASH_TAG, line) }
        runCatching { snapshotFile.delete() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reportPreviousProcessExit() {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val exit = runCatching {
            activityManager.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull()
        }.getOrNull() ?: return

        val level = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            -> LogLevel.Error

            else -> LogLevel.Info
        }
        eventLog.write(
            level,
            CRASH_TAG,
            buildString {
                append("Android recorded the previous process exit")
                append(" reason=")
                append(exitReasonName(exit.reason))
                append(" reasonCode=")
                append(exit.reason)
                append(" status=")
                append(exit.status)
                append(" importance=")
                append(exit.importance)
                append(" timestampMs=")
                append(exit.timestamp)
            },
        )
    }

    private fun capture(throwable: Throwable) {
        val lines = mutableListOf<String>()
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = throwable
        var cause = 0
        while (current != null && cause < MAX_CAUSES && seen.add(current)) {
            lines += "exception[$cause]=${current.javaClass.name}"
            current.stackTrace
                .take(MAX_FRAMES_PER_CAUSE)
                .forEachIndexed { index, frame ->
                    lines +=
                        "frame[$cause,$index]=${frame.className}.${frame.methodName}" +
                        "(${frame.fileName}:${frame.lineNumber})"
                }
            current = current.cause
            cause += 1
        }

        eventLog.events.value
            .takeLast(MAX_EVENT_LINES)
            .forEach { event ->
                // `event.line` has already passed through RedactingLogger before EventLog received it.
                lines += "event=${event.at} ${event.level} ${event.tag} ${event.line}"
            }

        snapshotFile.writeText(lines.joinToString(separator = "\n"))
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit-self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization-failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission-change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive-resource-usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user-requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user-stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency-died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package-state-change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package-updated"
        else -> "unknown"
    }

    private companion object {
        const val FILE_NAME = "pr83-crash-snapshot.txt"
        const val CRASH_TAG = "Crash"
        const val MAX_CAUSES = 5
        const val MAX_FRAMES_PER_CAUSE = 20
        const val MAX_EVENT_LINES = 80
        const val MAX_RESTORED_LINES = 200
    }
}
