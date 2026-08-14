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
 * ADR-0013 — finished is a time remaining, the listener chooses it, and the server overrides it.
 *
 * The first six cases are the rule as it stood when the threshold was a constant, kept because the numbers
 * they pin have not changed. The rest are the two things PR 2 of the Phase 2 closeout added: a configurable
 * value, and a library's own rule **inherited** rather than merged.
 *
 * There is deliberately no percentage case. The owner rejected the unit — 95% of a hundred-hour book leaves
 * five hours to go — and the type has no percentage in it to test.
 */
class FinishedThresholdTest {

    private val default = FinishedThreshold()

    @Test
    fun `a book with thirty seconds left is finished`() {
        assertTrue(default.isFinished(position = 9.minutes + 30.seconds, duration = 10.minutes))
    }

    @Test
    fun `a book with thirty-one seconds left is not`() {
        assertFalse(default.isFinished(position = 9.minutes + 29.seconds, duration = 10.minutes))
    }

    /**
     * The reason ADR-0013 deviates from PLAY-004's literal wording, as an assertion.
     *
     * Ninety-five per cent of a ten-hour book is thirty minutes from the end. Under the requirement as
     * written the app would call that finished; it does not, at any setting.
     */
    @Test
    fun `ninety-five per cent of a long book is not finished`() {
        assertFalse(default.isFinished(position = 9.hours + 30.minutes, duration = 10.hours))
        val widest = FinishedThreshold(configured = FinishedThreshold.Range.endInclusive)
        assertFalse(widest.isFinished(position = 9.hours + 30.minutes, duration = 10.hours))
    }

    /** A position past the end — a clamped sync, a rescanned file — is finished, not negative. */
    @Test
    fun `a position past the end is finished`() {
        assertTrue(default.isFinished(position = 11.hours, duration = 10.hours))
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
        assertFalse(default.isFinished(position = 5.minutes, duration = Duration.ZERO))
        val inherited = FinishedThreshold(library = 10.minutes)
        assertFalse(inherited.isFinished(position = 5.minutes, duration = Duration.ZERO))
    }

    /** A book shorter than the threshold is finished the moment it starts — and that is correct. */
    @Test
    fun `a book shorter than the threshold is finished from the start`() {
        assertTrue(default.isFinished(position = Duration.ZERO, duration = 8.seconds))
    }

    // --- The listener's setting (PRODUCT_SPEC SET-002) ----------------------------------------------

    /** The setting moves the line, in both directions. */
    @Test
    fun `the configured value decides where the line is`() {
        val eager = FinishedThreshold(configured = 120.seconds)
        assertTrue(eager.isFinished(position = 8.minutes + 1.seconds, duration = 10.minutes))

        val patient = FinishedThreshold(configured = 5.seconds)
        assertFalse(patient.isFinished(position = 9.minutes + 54.seconds, duration = 10.minutes))
        assertTrue(patient.isFinished(position = 9.minutes + 55.seconds, duration = 10.minutes))
    }

    /** A value from outside the range — a stored row, a typed number — is pulled into it rather than used. */
    @Test
    fun `a value outside the range is coerced into it`() {
        assertEquals(5.seconds, FinishedThreshold.coerce(Duration.ZERO))
        assertEquals(5.seconds, FinishedThreshold.coerce((-1).minutes))
        assertEquals(120.seconds, FinishedThreshold.coerce(1.hours))
        assertEquals(30.seconds, FinishedThreshold.coerce(30.seconds))
    }

    /** Every offered choice is a legal one, which is not automatic — the presets and the range are separate. */
    @Test
    fun `every preset is inside the range`() {
        assertEquals(FinishedThreshold.Presets, FinishedThreshold.Presets.map(FinishedThreshold::coerce))
    }

    // --- The library's rule, inherited (PRODUCT_SPEC PLAY-004) --------------------------------------

    /**
     * The owner's instruction, as an assertion: *"inherit from the web interface."*
     *
     * The capture server's libraries read `markAsFinishedTimeRemaining: 10` against a default setting of 30.
     * The **library's** number is the one in force, so a book with twenty seconds left is *not* finished —
     * which is exactly what the Audiobookshelf web interface would say about the same book.
     *
     * A previous build took `max(30, 10)` here and finished the book twenty seconds before the web interface
     * agreed. That is the disagreement inheriting removes.
     */
    @Test
    fun `a library that sets a rule overrides the listener's setting`() {
        val threshold = FinishedThreshold(configured = 30.seconds, library = 10.seconds)

        assertEquals(10.seconds, threshold.effective)
        assertTrue(threshold.isInherited)
        assertFalse(threshold.isFinished(position = 9.minutes + 40.seconds, duration = 10.minutes))
        assertTrue(threshold.isFinished(position = 9.minutes + 50.seconds, duration = 10.minutes))
    }

    /** And in the other direction, where the library is the more patient of the two. */
    @Test
    fun `a library asking for longer also wins`() {
        val threshold = FinishedThreshold(configured = 30.seconds, library = 90.seconds)

        assertEquals(90.seconds, threshold.effective)
        assertTrue(threshold.isFinished(position = 8.minutes + 31.seconds, duration = 10.minutes))
    }

    /** A library with no rule leaves the setting standing — `null` is not "finish at 0 s left". */
    @Test
    fun `a library with no rule leaves the setting alone`() {
        val threshold = FinishedThreshold(configured = 45.seconds, library = null)

        assertEquals(45.seconds, threshold.effective)
        assertFalse(threshold.isInherited)
        assertTrue(threshold.isFinished(position = 9.minutes + 15.seconds, duration = 10.minutes))
    }

    /**
     * The server's seconds, converted once — and a nonsense value dropped rather than honoured as zero.
     *
     * A library is **not** coerced into the listener's range. The range bounds what a person may choose; a
     * server may legitimately want ten seconds or ten minutes, and clamping it here would be the arguing this
     * design exists to stop.
     */
    @Test
    fun `a library rule keeps the server's own number`() {
        assertEquals(10.seconds, FinishedThreshold.libraryRule(10))
        assertEquals(600.seconds, FinishedThreshold.libraryRule(600), "not clamped to the listener's range")
        assertEquals(Duration.ZERO, FinishedThreshold.libraryRule(0), "zero is a rule a server may hold")
        assertEquals(null, FinishedThreshold.libraryRule(null))
        assertEquals(null, FinishedThreshold.libraryRule(-5), "a negative is not a rule")
    }
}
