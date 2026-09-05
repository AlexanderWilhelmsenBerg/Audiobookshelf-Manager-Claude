package com.example.shelfplayer.diagnostics

import android.content.Context
import com.example.shelfplayer.core.common.log.LoggedEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

internal enum class CrashReportSource(val wireName: String) {
    UncaughtException("uncaught_exception"),
    ProcessExit("process_exit"),
}

internal data class CrashReportMeta(
    val source: CrashReportSource,
    val occurredAtEpochMs: Long,
)

internal data class FormattedCrashReport(
    val meta: CrashReportMeta,
    val text: String,
)

internal data class StoredCrashReport(
    val meta: CrashReportMeta,
    val text: String,
)

/**
 * PRODUCT_SPEC 14.4 / 14.5 — the one small piece of diagnostics allowed to survive process death.
 *
 * The normal [com.example.shelfplayer.core.common.log.EventLog] remains memory-only. On a fatal Java
 * exception, a bounded tail of its already-redacted lines is copied into one app-private report under
 * `noBackupFilesDir`. Android's process-exit record can fill the same slot after an ANR/native crash where
 * our exception handler never ran.
 *
 * Only the latest report is retained. Nothing is uploaded, shared, backed up or written to public storage,
 * and clearing the report does not clear the marker that says an old Android exit record was already seen.
 */
@Singleton
class CrashReportStore @Inject constructor(@ApplicationContext context: Context) {
    private val storage = CrashReportFile(File(context.noBackupFilesDir, DIRECTORY_NAME))
    private val _report = MutableStateFlow(storage.read()?.text)

    val report: StateFlow<String?> = _report.asStateFlow()

    internal fun recordUncaught(
        at: Instant,
        app: String,
        sdk: Int,
        mainThread: Boolean,
        throwable: Throwable,
        events: List<LoggedEvent>,
    ) {
        storage.write(
            CrashReportFormatter.uncaught(
                at = at,
                app = app,
                sdk = sdk,
                mainThread = mainThread,
                throwable = throwable,
                events = events,
            ),
        )
    }

    internal fun recordProcessExit(
        at: Instant,
        app: String,
        sdk: Int,
        reason: String,
        reasonCode: Int,
        status: Int,
        importance: Int,
    ) {
        val formatted = CrashReportFormatter.processExit(
            at = at,
            app = app,
            sdk = sdk,
            reason = reason,
            reasonCode = reasonCode,
            status = status,
            importance = importance,
        )
        if (storage.write(formatted)) _report.value = formatted.text
    }

    internal fun latestMeta(): CrashReportMeta? = storage.read()?.meta

    internal fun handledExitTimestamp(): Long = storage.handledExitTimestamp()

    internal fun markExitHandled(timestamp: Long) {
        storage.markExitHandled(timestamp)
    }

    fun clear() {
        if (storage.clearReport()) _report.value = null
    }

    private companion object {
        const val DIRECTORY_NAME = "diagnostics"
    }
}

/** File operations are isolated so the persistence contract can be exercised by an ordinary JVM test. */
internal class CrashReportFile(private val directory: File) {
    private val lock = Any()
    private val reportFile = File(directory, REPORT_FILE_NAME)
    private val handledExitFile = File(directory, HANDLED_EXIT_FILE_NAME)

    fun read(): StoredCrashReport? = synchronized(lock) {
        val text = readText(reportFile)?.take(MAX_REPORT_CHARS) ?: return@synchronized null
        val source = headerValue(text, "source")
            ?.let { value -> CrashReportSource.entries.firstOrNull { it.wireName == value } }
            ?: return@synchronized null
        val occurredAt = headerValue(text, "occurredEpochMs")?.toLongOrNull() ?: return@synchronized null
        StoredCrashReport(CrashReportMeta(source, occurredAt), text)
    }

    fun write(report: FormattedCrashReport): Boolean = synchronized(lock) {
        atomicWrite(reportFile, report.text.take(MAX_REPORT_CHARS))
    }

    fun clearReport(): Boolean = synchronized(lock) {
        val removed = !reportFile.exists() || reportFile.delete()
        File(directory, "$REPORT_FILE_NAME.tmp").delete()
        removed
    }

    fun handledExitTimestamp(): Long = synchronized(lock) { handledExitTimestampUnlocked() }

    fun markExitHandled(timestamp: Long): Boolean = synchronized(lock) {
        if (timestamp <= handledExitTimestampUnlocked()) return@synchronized true
        atomicWrite(handledExitFile, timestamp.toString())
    }

    private fun handledExitTimestampUnlocked(): Long = readText(handledExitFile)?.trim()?.toLongOrNull() ?: 0L

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun readText(file: File): String? = try {
        if (file.exists()) file.readText() else null
    } catch (_: Exception) {
        null
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun atomicWrite(target: File, text: String): Boolean = try {
        if (!directory.exists() && !directory.mkdirs()) return false
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeText(text)
        if (target.exists() && !target.delete()) {
            target.writeText(text)
            temporary.delete()
            return true
        }
        if (!temporary.renameTo(target)) {
            target.writeText(text)
            temporary.delete()
        }
        true
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun headerValue(text: String, key: String): String? = text
        .lineSequence()
        .take(HEADER_LINES)
        .firstOrNull { line -> line.startsWith("$key: ") }
        ?.substringAfter(": ")

    private companion object {
        const val REPORT_FILE_NAME = "last-crash.txt"
        const val HANDLED_EXIT_FILE_NAME = "last-handled-exit.txt"
        const val MAX_REPORT_CHARS = 64_000
        const val HEADER_LINES = 10
    }
}
