package com.example.shelfplayer.core.common.log

import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactingLoggerTest {

    /**
     * A local sink rather than `:core:testing`'s.
     *
     * `:core:testing` depends on `:core:common`, so using it here would point this module's tests
     * back at a module that depends on it — technically buildable, but the kind of edge the build
     * should not have to rely on. Every other module uses the shared one.
     */
    private class TestSink : LogSink {
        val lines = mutableListOf<Triple<LogLevel, String, String>>()
        val text: String get() = lines.joinToString(separator = "\n") { it.third }

        override fun write(level: LogLevel, tag: String, line: String) {
            lines += Triple(level, tag, line)
        }
    }

    private val sink = TestSink()
    private val logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default))

    @Test
    fun `renders category as the tag and message plus fields as the line`() {
        logger.info(
            LogCategory.Sync,
            "Library refresh completed",
            LogField.Count("libraries", 2),
            LogField.Count("books", 7),
            correlationId = "abc-123",
        )

        val (level, tag, line) = sink.lines.single()
        assertEquals(LogLevel.Info, level)
        assertEquals("Sync", tag)
        assertEquals("Library refresh completed cid=abc-123 libraries=2 books=7", line)
    }

    /**
     * The end-to-end version of PRODUCT_SPEC AUTH-003: not "the redactor works" but "a line that
     * reaches the sink contains no secret".
     */
    @Test
    fun `no private value reaches the sink`() {
        logger.warn(
            LogCategory.Network,
            "Request failed",
            LogField.Secret("authorization"),
            LogField.ServerHost("host", "books.example.org"),
            LogField.MediaTitle("title", "The Salt Harbour"),
            LogField.Username("user", "alexandra"),
            LogField.Url("url", "https://books.example.org/items?token=SECRET"),
            throwable = IOException("connect to books.example.org failed"),
        )

        val text = sink.text
        assertFalse(text.contains("books.example.org"))
        assertFalse(text.contains("SECRET"))
        assertFalse(text.contains("Salt Harbour"))
        assertFalse(text.contains("alexandra"))
        assertTrue(text.contains("error=java.io.IOException"))
    }

    @Test
    fun `each level reaches the sink with its own severity`() {
        logger.debug(LogCategory.App, "d")
        logger.info(LogCategory.App, "i")
        logger.warn(LogCategory.App, "w")
        logger.error(LogCategory.App, "e")

        assertEquals(
            listOf(LogLevel.Debug, LogLevel.Info, LogLevel.Warn, LogLevel.Error),
            sink.lines.map { it.first },
        )
    }
}
