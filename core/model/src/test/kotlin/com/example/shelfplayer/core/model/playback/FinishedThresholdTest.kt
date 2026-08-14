package com.example.shelfplayer.core.model.playback

import com.example.shelfplayer.core.model.library.FinishedRule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * ADR-0013 — finished is a time remaining, the listener chooses it, and the library can insist on more.
 *
 * The first six tests are the rule as it stood when the threshold was a constant, kept because the numbers
 * they pin have not changed. The rest are the two halves ADR-0013 always described and PR 2 of the Phase 2
 * closeout finally implemented: a configurable value, and a library that gets a say.
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
     * written the app would call that finished; it does not — unless the *library* asks for a percentage,
     * which is the case further down.
     */
    @Test
    fun `ninety-five per cent of a long book is not finished`() {
        assertFalse(default.isFinished(position = 9.hours + 30.minutes, duration = 10.hours))
    }

    /** A position past the end — a clamped sync, a rescanned file — is finished, not negative. */
    @Test
    fun `a position past the end is finished`() {
        assertTrue(default.isFinished(position = 11.hours, duration = 10.hours))
    }

    /**
     * PRODUCT priority 2 — a book whose duration is unknown is never finished by either rule.
     *
     * With no end to measure from, "thirty seconds remaining" and "ninety-five per cent" both have no
     * meaning, and guessing would mark a book done that the listener had barely started. The library's
     * percentage rule is included in the check, because that is the branch a reader would most easily leave
     * outside the guard.
     */
    @Test
    fun `a book with no known duration is never finished`() {
        assertFalse(default.isFinished(position = 5.minutes, duration = Duration.ZERO))
        val eager = FinishedThreshold(library = FinishedRule.of(timeRemainingSeconds = 600, percentComplete = 1.0))
        assertFalse(eager.isFinished(position = 5.minutes, duration = Duration.ZERO))
    }

    /** A book shorter than the threshold is finished the moment it starts — and that is correct. */
    @Test
    fun `a book shorter than the threshold is finished from the start`() {
        assertTrue(default.isFinished(position = Duration.ZERO, duration = 8.seconds))
    }

    // --- The listener's half (PRODUCT_SPEC SET-002) ------------------------------------------------

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

    // --- The library's half (PRODUCT_SPEC PLAY-004) ------------------------------------------------

    /**
     * ADR-0013's `max`, in the direction the capture server actually has.
     *
     * `libraries.json` reads `markAsFinishedTimeRemaining: 10` against a default of 30. The app is the more
     * eager of the two and finishes first, which the server then accepts — so the number in force is still
     * the listener's.
     */
    @Test
    fun `a library less eager than the setting does not move the line`() {
        val threshold = FinishedThreshold(
            configured = 30.seconds,
            library = FinishedRule.of(timeRemainingSeconds = 10, percentComplete = null),
        )

        assertEquals(30.seconds, threshold.effective)
        assertTrue(threshold.isFinished(position = 9.minutes + 30.seconds, duration = 10.minutes))
    }

    /**
     * The other direction, which is the one the rule exists for.
     *
     * A library configured at sixty seconds marks a book finished with a minute left whatever this app
     * thinks. If the app disagreed, the same book would be finished on the server and unfinished here, and
     * would flip on every sync — so the library's number wins and the two stay in step.
     */
    @Test
    fun `a library more eager than the setting wins`() {
        val threshold = FinishedThreshold(
            configured = 30.seconds,
            library = FinishedRule.of(timeRemainingSeconds = 60, percentComplete = null),
        )

        assertEquals(60.seconds, threshold.effective)
        assertTrue(threshold.isFinished(position = 9.minutes + 1.seconds, duration = 10.minutes))
        assertFalse(threshold.isFinished(position = 8.minutes + 59.seconds, duration = 10.minutes))
    }

    /** A library with no opinion contributes nothing rather than zero — `null` is not "finish at 0 s left". */
    @Test
    fun `a library with no opinion leaves the setting alone`() {
        assertEquals(30.seconds, FinishedThreshold(library = FinishedRule.Unset).effective)
        assertFalse(
            FinishedThreshold(library = FinishedRule.Unset)
                .isFinished(position = 5.minutes, duration = 10.minutes),
        )
    }

    /**
     * The percentage clause, which no capture has ever produced a value for.
     *
     * Carried because the same asymmetry applies — the app must not be less eager than a server that
     * finishes on a fraction — and written to the server's documented field rather than to an observed
     * response (PRODUCT_SPEC 22.5). Ninety per cent of a ten-hour book is an hour from the end, which the
     * time rule alone would never call finished.
     */
    @Test
    fun `a library percentage finishes a long book the time rule would not`() {
        val threshold = FinishedThreshold(
            configured = 30.seconds,
            library = FinishedRule.of(timeRemainingSeconds = null, percentComplete = 90.0),
        )

        assertTrue(threshold.isFinished(position = 9.hours, duration = 10.hours))
        assertFalse(threshold.isFinished(position = 8.hours + 59.minutes, duration = 10.hours))
    }

    /**
     * The server's units, converted exactly once.
     *
     * 95 is a percentage on the wire and 0.95 is the fraction this type holds. A rule that read 95 as a
     * fraction would call every book finished at its first write; one that read 0.95 as a percentage would
     * never finish anything.
     */
    @Test
    fun `the server's percentage becomes a fraction`() {
        assertEquals(0.95, FinishedRule.of(timeRemainingSeconds = null, percentComplete = 95.0).percentComplete)
        assertEquals(
            0.95,
            FinishedRule.stored(timeRemainingSeconds = null, fractionComplete = 0.95).percentComplete,
        )
    }

    /**
     * A nonsensical rule is dropped rather than clamped, in both factories.
     *
     * A percentage of 400 is not a library asking for anything sensible; honouring it as 4.0 would mark
     * nothing finished and clamping it to 1.0 would mark everything finished at the last sample. Neither is
     * a rule, so there is no rule.
     */
    @Test
    fun `an out-of-range rule is no rule at all`() {
        assertEquals(null, FinishedRule.of(timeRemainingSeconds = null, percentComplete = 400.0).percentComplete)
        assertEquals(null, FinishedRule.of(timeRemainingSeconds = null, percentComplete = 0.0).percentComplete)
        assertEquals(null, FinishedRule.of(timeRemainingSeconds = -5, percentComplete = null).timeRemaining)
        assertEquals(
            null,
            FinishedRule.stored(timeRemainingSeconds = null, fractionComplete = 4.0).percentComplete,
        )
    }
}
