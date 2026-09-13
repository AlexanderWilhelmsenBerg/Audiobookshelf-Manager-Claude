package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.PlaybackEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackStopHistoryCauseTest {
    private val cause = PlaybackStopHistoryCause()

    @Test
    fun `ordinary pause stays a pause`() {
        assertEquals(PlaybackEvent.Pause, cause.eventFor(playWhenReady = false))
    }

    @Test
    fun `sleep timer expiry replaces exactly one pause marker`() {
        cause.markSleepTimerExpiry()

        assertEquals(PlaybackEvent.SleepTimerExpired, cause.eventFor(playWhenReady = false))
        assertEquals(PlaybackEvent.Pause, cause.eventFor(playWhenReady = false))
    }

    @Test
    fun `play clears an unconsumed timer cause`() {
        cause.markSleepTimerExpiry()

        assertEquals(PlaybackEvent.Play, cause.eventFor(playWhenReady = true))
        assertEquals(PlaybackEvent.Pause, cause.eventFor(playWhenReady = false))
    }
}
