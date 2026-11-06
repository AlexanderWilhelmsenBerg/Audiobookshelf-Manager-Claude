package com.example.shelfplayer.playback

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.LogLevel
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Media3TransferTraceTest {
    @Test
    fun `trace bounds history and records virtual and wall time independently`() {
        var virtual = 100L
        var wall = 1_000_000L
        val trace = Media3TransferTrace({ virtual }, { wall })
        repeat(130) { trace.record("event-$it") }
        virtual += 2
        wall += 9_000_000
        trace.record("last")
        val rendered = trace.render()
        assertEquals(129, rendered.lines().size)
        assertTrue(rendered.contains("dropped=3"))
        assertFalse(rendered.contains(" event-0\n"))
        assertTrue(rendered.endsWith("131 virtualMs=2 wallUs=9000 last"))
    }

    @Test
    fun `failure retains original assertion and reports an unavailable snapshot`() {
        val trace = Media3TransferTrace({ 0 }, { 0 })
        trace.record("message-delivered id=1")
        val failure = AssertionError("original failure")
        trace.attachTo(failure) { error("private exception detail") }
        assertEquals("original failure", failure.message)
        val detail = failure.suppressed.single().message.orEmpty()
        assertTrue(detail.contains("message-delivered id=1"))
        assertTrue(detail.contains("failure-snapshot-unavailable type=IllegalStateException"))
        assertFalse(detail.contains("private exception detail"))
    }

    @Test
    fun `logger retains routing fields without exporting private or arbitrary fields`() {
        val trace = Media3TransferTrace({ 0 }, { 0 })
        trace.log(
            LogEvent(
                LogLevel.Info,
                LogCategory.Playback,
                "Output transfer stage",
                listOf(
                    LogField.Public("stage", "VerifyingOutput"),
                    LogField.MediaTitle("title", "private-title"),
                    LogField.Public("unexpected", "private-value"),
                ),
            ),
        )
        assertTrue(trace.render().contains("stage=VerifyingOutput"))
        assertFalse(trace.render().contains("private-"))
    }
}
