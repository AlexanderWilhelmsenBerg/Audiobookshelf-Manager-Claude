package com.example.shelfplayer.core.model.playback

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * ADR-0013 — finished is a time remaining, and **the library on the server decides how much.**
 *
 * There is deliberately nothing here about a per-device setting: the app has none. The owner asked for the
 * app's number and the server's to match, and the only way they can is for the app to have no number. There is
 * also nothing about a percentage — 95% of a hundred-hour book leaves five hours to go, and the type has no
 * percentage in it to test.
 *
 * The first six cases are the rule as it stood when the threshold was a constant, kept because the numbers they
 * pin are still the fallback's.
 */
class FinishedThresholdTest {

    /** A library that has told the app nothing, which falls back to ADR-0013's thirty seconds. */
    private val fallback = FinishedThreshold()

    @Test
    fun `a book with thirty seconds left is finished`() {
        assertTrue(fallback.isFinished(position = 9.minutes + 30.seconds, duration = 10.minutes))
    }

    @Test
    fun `a book with thirty-one seconds left is not`() {
        assertFalse(fallback.isFinished(position = 9.minutes + 29.seconds, duration = 10.minutes))
    }

    /**
     * The reason ADR-0013 deviates from PLAY-004's literal wording, as an assertion.
     *
     * Ninety-five per cent of a ten-hour book is thirty minutes from the end. Under the requirement as written
     * the app would call that finished. It does not, at any library's setting short of half an hour.
     */
    @Test
    fun `ninety-five per cent of a long book is not finished`() {
        assertFalse(fallback.isFinished(position = 9.hours + 30.minutes, duration = 10.hours))
        val generous = FinishedThreshold(library = 120.seconds)
        assertFalse(generous.isFinished(position = 9.hours + 30.minutes, duration = 10.hours))
    }

    /** A position past the end — a clamped sync, a rescanned file — is finished, not negative. */
    @Test
    fun `a position past the end is finished`() {
        assertTrue(fallback.isFinished(position = 11.hours, duration = 10.hours))
    }

    /**
     * PRODUCT priority 2 — a book whose duration is unknown is never finished.
     *
     * With no end to measure from, "thirty seconds remaining" has no meaning, and guessing would mark a book
     * done that the listener had barely started. Tested with a library rule too, because that is the branch a
     * reader would most easily leave outside the guard.
     */
    @Test
    fun `a book with no known duration is never finished`() {
        assertFalse(fallback.isFinished(position = 5.minutes, duration = Duration.ZERO))
        assertFalse(FinishedThreshold(library = 10.minutes).isFinished(position = 5.minutes, duration = Duration.ZERO))
    }

    /** A book shorter than the threshold is finished the moment it starts — and that is correct. */
    @Test
    fun `a book shorter than the threshold is finished from the start`() {
        assertTrue(fallback.isFinished(position = Duration.ZERO, duration = 8.seconds))
    }

    // --- The library's rule, which is the only rule (PRODUCT_SPEC PLAY-004) -------------------------

    /**
     * The capture server's own value, and the case that matters most because it is nearly everybody's.
     *
     * `libraries.json` reads `markAsFinishedTimeRemaining: 10`. So a book with twenty seconds left is **not**
     * finished — which is exactly what the Audiobookshelf web interface would say about the same book. Two
     * earlier builds disagreed with the server here: one used a hard-coded thirty seconds, the next took the
     * `max` of thirty and ten. Both finished the book twenty seconds early.
     */
    @Test
    fun `the library's ten seconds is the rule, not the fallback's thirty`() {
        val threshold = FinishedThreshold(library = 10.seconds)

        assertEquals(10.seconds, threshold.effective)
        assertFalse(threshold.isFinished(position = 9.minutes + 40.seconds, duration = 10.minutes))
        assertTrue(threshold.isFinished(position = 9.minutes + 50.seconds, duration = 10.minutes))
    }

    /** And a library more generous than the fallback is honoured just as literally. */
    @Test
    fun `a library asking for longer is honoured`() {
        val threshold = FinishedThreshold(library = 90.seconds)

        assertEquals(90.seconds, threshold.effective)
        assertTrue(threshold.isFinished(position = 8.minutes + 31.seconds, duration = 10.minutes))
        assertFalse(threshold.isFinished(position = 8.minutes + 29.seconds, duration = 10.minutes))
    }

    /** A library that has said nothing gets the fallback — `null` is not "finish at 0 s left". */
    @Test
    fun `a library with no rule falls back rather than finishing at zero`() {
        assertEquals(30.seconds, FinishedThreshold(library = null).effective)
        assertFalse(FinishedThreshold(library = null).isFinished(position = 5.minutes, duration = 10.minutes))
    }

    /**
     * The server's seconds, converted once — and a nonsense value dropped rather than honoured as zero.
     *
     * A library is deliberately **not** bounded. A server may legitimately want ten seconds or ten minutes,
     * and second-guessing it here would be the arguing this design exists to stop.
     */
    @Test
    fun `a library rule keeps the server's own number`() {
        assertEquals(10.seconds, FinishedThreshold.libraryRule(10))
        assertEquals(600.seconds, FinishedThreshold.libraryRule(600), "not bounded to anything of ours")
        assertEquals(Duration.ZERO, FinishedThreshold.libraryRule(0), "zero is a rule a server may hold")
        assertEquals(null, FinishedThreshold.libraryRule(null))
        assertEquals(null, FinishedThreshold.libraryRule(-5), "a negative is not a rule")
    }

    /** A library asking for zero finishes a book only at its very end, which is what zero means. */
    @Test
    fun `a library asking for zero finishes only at the end`() {
        val exact = FinishedThreshold(library = Duration.ZERO)

        assertFalse(exact.isFinished(position = 10.minutes - 1.seconds, duration = 10.minutes))
        assertTrue(exact.isFinished(position = 10.minutes, duration = 10.minutes))
    }
}
