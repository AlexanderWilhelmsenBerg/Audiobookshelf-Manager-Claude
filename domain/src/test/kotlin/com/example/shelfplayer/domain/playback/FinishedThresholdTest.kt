package com.example.shelfplayer.domain.playback

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** ADR-0013 — finished is a time remaining, and this pins the number and its edges. */
class FinishedThresholdTest {

    @Test
    fun `a book with thirty seconds left is finished`() {
        assertTrue(FinishedThreshold.isFinished(position = 9.minutes + 30.seconds, duration = 10.minutes))
    }

    @Test
    fun `a book with thirty-one seconds left is not`() {
        assertFalse(FinishedThreshold.isFinished(position = 9.minutes + 29.seconds, duration = 10.minutes))
    }

    /**
     * The reason ADR-0013 deviates from PLAY-004's literal wording, as an assertion.
     *
     * Ninety-five per cent of a ten-hour book is thirty minutes from the end. Under the requirement as
     * written the app would call that finished; it does not.
     */
    @Test
    fun `ninety-five per cent of a long book is not finished`() {
        assertFalse(FinishedThreshold.isFinished(position = 9.hours + 30.minutes, duration = 10.hours))
    }

    /** A position past the end — a clamped sync, a rescanned file — is finished, not negative. */
    @Test
    fun `a position past the end is finished`() {
        assertTrue(FinishedThreshold.isFinished(position = 11.hours, duration = 10.hours))
    }

    /**
     * PRODUCT priority 2 — a book whose duration is unknown is never finished by this rule.
     *
     * With no end to measure from, "thirty seconds remaining" has no meaning, and guessing would mark a
     * book done that the listener had barely started.
     */
    @Test
    fun `a book with no known duration is never finished`() {
        assertFalse(FinishedThreshold.isFinished(position = 5.minutes, duration = Duration.ZERO))
    }

    /** A book shorter than the threshold is finished the moment it starts — and that is correct. */
    @Test
    fun `a book shorter than the threshold is finished from the start`() {
        assertTrue(FinishedThreshold.isFinished(position = Duration.ZERO, duration = 8.seconds))
    }
}
