package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.testing.TestAppClock
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Phase 3 trace extraction: correlation and diagnostic evidence never become playback authority. */
internal class AndroidAutoTraceRecorderTest {
    private val events = mutableListOf<LogEvent>()
    private val logger = object : Logger {
        override fun log(event: LogEvent) {
            events += event
        }
    }
    private val clock = TestAppClock()
    private val recorder = AndroidAutoTraceRecorder(logger, clock) {
        listOf(LogField.Public("sessionEstablished", true))
    }

    @Test
    fun `focus and arrival reuse one correlation until its inclusive deadline`() {
        val focus = recorder.ensure("audio-focus-loss")
        clock.advanceBy(10.seconds)
        val arrival = recorder.ensure("controller-connect")
        assertEquals(focus, arrival)
        assertEquals("auto-1", arrival.correlationId)
        assertEquals(1, events.count { it.message == "Android Auto trace started" })

        clock.advanceBy(1.milliseconds)
        val next = recorder.ensure("new-ignition")
        assertEquals("auto-2", next.correlationId)
        assertEquals(2, events.count { it.message == "Android Auto trace started" })
        recorder.log("Car lifecycle continuity decision", fields = listOf(LogField.Public("status", "Armed")))
        assertEquals(next.correlationId, events.last().correlationId)
        assertEquals(LogCategory.AndroidAuto, events.last().category)
        assertEquals(
            1L,
            events.last().fields.filterIsInstance<LogField.Millis>().single { it.key == "afterTrace" }.value,
        )
    }

    @Test
    fun `only known car packages may associate with an active trace`() {
        assertNull(recorder.forController("car", setOf("car")))
        val trace = recorder.ensure("first-bind")
        assertEquals(trace, recorder.forController("car", setOf("car")))
        assertNull(recorder.forController("untrusted", setOf("car")))
    }

    @Test
    fun `unattributed diagnostics do not invent correlation or snapshot evidence`() {
        recorder.log("Media3 player changed", fields = listOf(LogField.Public("state", "idle")))
        assertNull(events.single().correlationId)
        assertTrue(events.single().fields.none { it.key == "traceSource" || it.key == "afterTrace" })
        recorder.ensure("controller-connect")
        val beginning = events.last()
        assertEquals("controller-connect", beginning.public("traceSource"))
        assertEquals("true", beginning.public("sessionEstablished"))
        assertEquals("controller-connect", beginning.public("source"))
    }

    @Test
    fun `media rows are bounded and keep titles out of unredacted trace values`() {
        val trace = recorder.ensure("browse")
        events.clear()
        val item = MediaItem.Builder()
            .setMediaId("private-book-id")
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle("A very private book name").setIsPlayable(true).build(),
            ).build()
        recorder.logItems(trace, "onGetChildren", "answered", List(40) { item })
        assertTrue(events.isNotEmpty())
        assertEquals("Android Auto media item handoff", events.first().message)
        assertTrue(events.size <= AndroidAutoDiagnostics.MAX_MEDIA_HANDOFF_ITEMS + 1)
        assertTrue(events.all { it.correlationId == trace.correlationId })
        assertFalse(
            events.flatMap { event -> event.fields }.filterIsInstance<LogField.Public>()
                .any { it.value.contains("A very private book name") || it.value.contains("private-book-id") },
        )
    }

    private fun LogEvent.public(key: String): String? =
        fields.filterIsInstance<LogField.Public>().firstOrNull { it.key == key }?.value
}
