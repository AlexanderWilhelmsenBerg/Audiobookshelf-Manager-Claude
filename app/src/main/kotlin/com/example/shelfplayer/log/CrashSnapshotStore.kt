package com.example.shelfplayer.log

import android.content.Context
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

    /** Restore one previous crash into the in-app Event Log, then remove its on-disk copy. */
    fun restore() {
        val lines = runCatching {
            if (snapshotFile.isFile) snapshotFile.readLines().take(MAX_RESTORED_LINES) else emptyList()
        }.getOrDefault(emptyList())
        if (lines.isEmpty()) return

        eventLog.write(LogLevel.Error, CRASH_TAG, "Recovered diagnostics from the previous process crash")
        lines.forEach { line -> eventLog.write(LogLevel.Debug, CRASH_TAG, line) }
        runCatching { snapshotFile.delete() }
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

    private companion object {
        const val FILE_NAME = "pr83-crash-snapshot.txt"
        const val CRASH_TAG = "Crash"
        const val MAX_CAUSES = 5
        const val MAX_FRAMES_PER_CAUSE = 20
        const val MAX_EVENT_LINES = 80
        const val MAX_RESTORED_LINES = 200
    }
}
