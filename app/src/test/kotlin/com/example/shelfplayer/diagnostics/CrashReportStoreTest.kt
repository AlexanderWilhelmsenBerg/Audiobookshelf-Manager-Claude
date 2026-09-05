package com.example.shelfplayer.diagnostics

import com.example.shelfplayer.core.common.log.LogLevel
import com.example.shelfplayer.core.common.log.LoggedEvent
import org.junit.Test
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashReportStoreTest {
    @Test
    fun `uncaught report keeps code evidence but never throwable messages`() {
        val secretMessage = "https://private.example/books/The Secret Book"
        val cause = IllegalArgumentException("A private book title")
        val throwable = IllegalStateException(secretMessage, cause).apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "com.example.shelfplayer.playback.SessionSyncCoordinator",
                    "syncNow",
                    "SessionSyncCoordinator.kt",
                    142,
                ),
                StackTraceElement("java.lang.Thread", "run", "Thread.java", 1),
            )
        }
        val events = listOf(
            LoggedEvent(Instant.EPOCH, LogLevel.Warn, "Playback", "profile=#A12B session=#91FF"),
        )

        val report = CrashReportFormatter.uncaught(
            at = Instant.EPOCH,
            app = "0.9.9 debug abc123 main",
            sdk = 36,
            mainThread = true,
            throwable = throwable,
            events = events,
        ).text

        assertFalse(report.contains(secretMessage))
        assertFalse(report.contains("A private book title"))
        assertTrue(report.contains("java.lang.IllegalStateException <- java.lang.IllegalArgumentException"))
        assertTrue(report.contains("SessionSyncCoordinator.syncNow(SessionSyncCoordinator.kt:142)"))
        assertTrue(report.contains("profile=#A12B session=#91FF"))
    }

    @Test
    fun `only the bounded redacted event tail is persisted`() {
        val events = (0 until 100).map { index ->
            LoggedEvent(Instant.ofEpochSecond(index.toLong()), LogLevel.Debug, "Playback", "line-$index")
        }

        val report = CrashReportFormatter.uncaught(
            at = Instant.EPOCH,
            app = "0.9.9 debug abc123 main",
            sdk = 36,
            mainThread = false,
            throwable = IllegalStateException(),
            events = events,
        ).text

        assertTrue(report.contains("[events] 80 of 100"))
        assertFalse(report.contains("line-0\n"))
        assertTrue(report.contains("line-99"))
    }

    @Test
    fun `report survives a new store and clear does not resurrect an old exit`() {
        val directory = createTempDirectory("bookwave-crash-report").toFile()
        try {
            val report = CrashReportFormatter.processExit(
                at = Instant.ofEpochMilli(1234),
                app = "0.9.9 release abc123 main",
                sdk = 36,
                reason = "ANR",
                reasonCode = 6,
                status = 0,
                importance = 100,
            )
            val first = CrashReportFile(directory)
            assertTrue(first.write(report))
            assertTrue(first.markExitHandled(1234))

            val reopened = CrashReportFile(directory)
            val stored = assertNotNull(reopened.read())
            assertEquals(report.meta, stored.meta)
            assertEquals(report.text, stored.text)
            assertEquals(1234, reopened.handledExitTimestamp())

            assertTrue(reopened.clearReport())
            assertNull(CrashReportFile(directory).read())
            assertEquals(1234, CrashReportFile(directory).handledExitTimestamp())
        } finally {
            directory.deleteRecursively()
        }
    }
}
