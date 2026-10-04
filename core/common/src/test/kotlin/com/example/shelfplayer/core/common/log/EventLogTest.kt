package com.example.shelfplayer.core.common.log

import com.example.shelfplayer.core.common.time.AppClock
import org.junit.Test
import java.io.IOException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

/** PRODUCT_SPEC 14.4/14.5: diagnostics expose only redacted, bounded, clearable history. */
class EventLogTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private val clock = object : AppClock {
        override fun now(): Instant = now

        override fun elapsed(): Duration = Duration.ZERO
    }
    private val sink = EventLog(clock)

    @Test
    fun `private fields and exception messages never reach the visible event history`() {
        val logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default))
        logger.error(
            LogCategory.Network,
            "Request failed",
            LogField.Secret("authorization"),
            LogField.ServerHost("host", "private.example.invalid"),
            LogField.Username("account", "fixture-user"),
            LogField.MediaTitle("title", "Private fixture title"),
            LogField.FilePath("path", "/private/fixture-recording.m4b"),
            LogField.Url("url", "https://private.example.invalid/items/123?token=fixture-secret"),
            throwable = IOException("fixture-secret at private.example.invalid"),
        )

        val event = sink.events.value.single()
        assertEquals(now, event.at)
        assertEquals(LogLevel.Error, event.level)
        assertEquals("Network", event.tag)
        assertTrue(event.isProblem)
        assertTrue(event.line.contains("authorization=<redacted>"))
        assertTrue(event.line.contains("error=java.io.IOException"))
        listOf(
            "private.example.invalid",
            "fixture-user",
            "Private fixture title",
            "fixture-recording",
            "fixture-secret",
        )
            .forEach { assertFalse(event.line.contains(it), "Private value reached the event history") }
    }

    @Test
    fun `ring retains only the newest 500 events in chronological order`() {
        repeat(501) { sink.write(LogLevel.Info, "App", "event-$it") }

        val events = sink.events.value
        assertEquals(500, events.size)
        assertEquals((1..500).map { "event-$it" }, events.map { it.line })
        events.forEach { assertEquals(now, it.at) }
    }

    @Test
    fun `clearing removes history and subsequent events start a fresh ring`() {
        sink.write(LogLevel.Warn, "Playback", "First event")
        val previousSnapshot = sink.events.value
        sink.clear()
        assertTrue(sink.events.value.isEmpty())
        sink.write(LogLevel.Info, "App", "After clear")

        assertEquals("After clear", sink.events.value.single().line)
        assertEquals("First event", previousSnapshot.single().line)
    }

    @Test
    fun `problem filter includes warnings and errors only`() {
        LogLevel.entries.forEach { sink.write(it, "App", "Severity sample") }

        assertEquals(listOf(LogLevel.Warn, LogLevel.Error), sink.events.value.filter { it.isProblem }.map { it.level })
    }
}
