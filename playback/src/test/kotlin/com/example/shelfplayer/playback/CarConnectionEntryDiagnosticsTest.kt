package com.example.shelfplayer.playback

import android.content.Intent
import android.os.Bundle
import androidx.media3.session.MediaSession
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.testing.TestAppClock
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarConnectionEntryDiagnosticsTest {
    @Test
    fun `real legacy bind and session lookup produce timing without asserting a car arrival`() {
        val events = mutableListOf<LogEvent>()
        val clock = TestAppClock()
        val service = Robolectric.buildService(PlaybackService::class.java).get().apply {
            this.clock = clock
            logger = object : Logger {
                override fun log(event: LogEvent) {
                    events += event
                }
            }
            carConnections = CarConnections(clock)
        }
        assertNull(service.onBind(Intent("android.media.browse.MediaBrowserService")))
        clock.advanceBy(120.milliseconds)
        val controller = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            "com.google.android.projection.gearhead",
            1,
            1,
            1,
            1,
            true,
            Bundle(),
            true,
        )
        assertNull(service.onGetSession(controller))
        assertFalse(service.carConnections.isConnected())

        val entries = events.filter { it.message == "Android Auto connection entry observed" }
        assertEquals(listOf("service-bind", "session-lookup", "session-lookup"), entries.map { it.public("stage") })
        assertEquals("legacy-browser", entries.first().public("kind"))
        assertEquals("known-car-package", entries.last().public("kind"))
        assertEquals("true", entries.last().public("trusted"))
        assertTrue(entries.all { it.correlationId == null }, "passive entry cannot open an ignition trace")
        assertEquals(120L, entries.last().fields.filterIsInstance<LogField.Millis>().single().value)
        assertTrue(
            events.none {
                it.message.contains("continuity issued Play") || it.message.contains("trace started")
            },
        )
        assertTrue(
            entries.flatMap { it.fields }.filterIsInstance<LogField.Public>().none {
                it.value.contains("gearhead") || it.value.contains("android.media")
            },
        )
    }

    @Test
    fun `connection entry timing shares existing trace without restarting its clock`() {
        val clock = TestAppClock()
        val events = mutableListOf<LogEvent>()
        val recorder = AndroidAutoTraceRecorder(
            object : Logger {
                override fun log(event: LogEvent) {
                    events += event
                }
            },
            clock,
        ) { emptyList() }
        val trace = recorder.ensure("audio-focus-loss")
        clock.advanceBy(250.milliseconds)
        recorder.connectionEntry("controller-connect", "known-car-package", true)
        assertEquals(trace, recorder.activeTrace)
        assertEquals(trace.correlationId, events.last().correlationId)
        assertEquals(1, events.count { it.message == "Android Auto trace started" })
        val times = events.last().fields.filterIsInstance<LogField.Millis>().associate { it.key to it.value }
        assertEquals(250L, times["afterTrace"])
        assertEquals(250L, times["elapsedRealtime"])
    }

    @Test
    fun `unknown bind action cannot leak its contents into timing fields`() {
        assertEquals("other-or-missing", CarConnectionEntryKind.forAction("private-action-with-identifiers"))
        assertEquals("other-or-missing", CarConnectionEntryKind.forAction(null))
        assertEquals("media-library", CarConnectionEntryKind.forAction("androidx.media3.session.MediaLibraryService"))
        assertEquals("media-session", CarConnectionEntryKind.forAction("androidx.media3.session.MediaSessionService"))
    }

    private fun LogEvent.public(key: String): String? =
        fields.filterIsInstance<LogField.Public>().firstOrNull { it.key == key }?.value
}
