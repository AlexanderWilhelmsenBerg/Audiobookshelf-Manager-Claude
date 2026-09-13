package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.PlaybackHistoryEntry
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class PlaybackHistoryProjectionTest {
    @Test
    fun `timer expiry replaces its matching generic pause regardless of write order`() {
        val pause = entry("pause", PlaybackEvent.Pause, atMs = 100, position = 3.hours)
        val timer = entry("timer", PlaybackEvent.SleepTimerExpired, atMs = 250, position = 3.hours)

        assertEquals(listOf(timer), listOf(pause, timer).canonicalStopHistory())
        assertEquals(listOf(timer), listOf(timer, pause).canonicalStopHistory())
    }

    @Test
    fun `ordinary pause remains when there is no matching timer stop`() {
        val pause = entry("pause", PlaybackEvent.Pause, atMs = 100, position = 3.hours)

        assertEquals(listOf(pause), listOf(pause).canonicalStopHistory())
    }

    @Test
    fun `nearby but different stop position is not collapsed`() {
        val pause = entry("pause", PlaybackEvent.Pause, atMs = 100, position = 3.hours)
        val timer = entry("timer", PlaybackEvent.SleepTimerExpired, atMs = 250, position = 3.hours + 2.seconds)

        assertEquals(listOf(pause, timer), listOf(pause, timer).canonicalStopHistory())
    }

    @Test
    fun `same position outside the timer pairing window is not collapsed`() {
        val pause = entry("pause", PlaybackEvent.Pause, atMs = 100, position = 3.hours)
        val timer = entry("timer", PlaybackEvent.SleepTimerExpired, atMs = 1_101, position = 3.hours)

        assertEquals(listOf(pause, timer), listOf(pause, timer).canonicalStopHistory())
    }

    private fun entry(id: String, event: PlaybackEvent, atMs: Long, position: Duration) = PlaybackHistoryEntry(
        id = id,
        event = event,
        from = null,
        to = position,
        detail = null,
        at = Instant.ofEpochMilli(atMs),
    )
}
