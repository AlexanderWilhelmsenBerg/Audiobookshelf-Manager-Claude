package com.example.shelfplayer.feature.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * PRODUCT_SPEC SYNC-002 — the gate in front of the freshness check, and the case it used to swallow.
 *
 * ### The device run this file exists for
 *
 * A log showed a Play with no check recorded against it at all: paused in the app at 12:18, resumed at
 * 12:19, under the thirty-second gate — so BookWave never asked the server, and the position the web
 * player had moved to was never adopted. The gate was suppressing exactly the case the check was written
 * to serve, because *leaving BookWave to move the book somewhere else and coming back* is a trip of
 * seconds, not minutes.
 *
 * The rule is now three transitions rather than one field, and these are the three.
 */
class ResumeFreshnessTest {

    /**
     * **Leaving the app forgets the pause**, however short it was. This is the reported case.
     *
     * One second between pausing and resuming, which the gate alone would skip — and a trip to the
     * background in between, which is the whole reason to ask.
     */
    @Test
    fun `leaving the app forces a check on a pause the gate would have skipped`() {
        val time = TestTimeSource()
        val freshness = ResumeFreshness(minimumPause = 30.seconds, timeSource = time)

        freshness.onPausedInApp()
        time += 1.seconds
        assertFalse(freshness.isCheckNeeded(), "a one-second pause in the app is not worth a request")

        freshness.onLeftApp()

        assertTrue(freshness.isCheckNeeded(), "the listener left; the book may have moved while they were gone")
    }

    /**
     * A pause and resume inside the app, under the gate, still skips — which is what the gate is for.
     *
     * Nobody who was holding the phone the whole time has been overtaken by another device, and the
     * request would be latency spent to learn nothing. This is the case that keeps Play instant.
     */
    @Test
    fun `a short pause without leaving the app skips the check`() {
        val time = TestTimeSource()
        val freshness = ResumeFreshness(minimumPause = 30.seconds, timeSource = time)

        freshness.onPausedInApp()
        time += 29.seconds

        assertFalse(freshness.isCheckNeeded())
    }

    /** At the threshold it checks: the boundary belongs to asking, which is the safe direction. */
    @Test
    fun `a pause exactly as long as the minimum checks`() {
        val time = TestTimeSource()
        val freshness = ResumeFreshness(minimumPause = 30.seconds, timeSource = time)

        freshness.onPausedInApp()
        time += 30.seconds

        assertTrue(freshness.isCheckNeeded())
    }

    /** And a genuinely long pause, which is the case the gate was originally written around. */
    @Test
    fun `a long pause checks`() {
        val time = TestTimeSource()
        val freshness = ResumeFreshness(minimumPause = 30.seconds, timeSource = time)

        freshness.onPausedInApp()
        time += 20.minutes

        assertTrue(freshness.isCheckNeeded())
    }

    /**
     * Nothing paused here yet, so nothing is known, so it checks.
     *
     * Every resume after a cold start, and every resume after a pause from the notification, the car or a
     * headset — none of which reach the ViewModel this belongs to. Erring towards asking costs two seconds
     * at worst; erring the other way resumes on a position somebody else has moved.
     */
    @Test
    fun `an unknown pause checks`() {
        val freshness = ResumeFreshness(minimumPause = 30.seconds, timeSource = TestTimeSource())

        assertTrue(freshness.isCheckNeeded())
    }

    /**
     * A second pause replaces the first, rather than the gate remembering the oldest one.
     *
     * Without this a listener who paused twenty minutes ago and has since paused again would be treated
     * as a twenty-minute pause and asked every time.
     */
    @Test
    fun `a later pause replaces an earlier one`() {
        val time = TestTimeSource()
        val freshness = ResumeFreshness(minimumPause = 30.seconds, timeSource = time)

        freshness.onPausedInApp()
        time += 20.minutes
        freshness.onPausedInApp()
        time += 1.seconds

        assertFalse(freshness.isCheckNeeded())
    }

    /**
     * Returning to the app and pausing again starts the gate over.
     *
     * The trip to the background invalidated whatever came before it; a pause performed *after* coming
     * back is a fresh, short, in-app pause like any other.
     */
    @Test
    fun `a pause after coming back is short again`() {
        val time = TestTimeSource()
        val freshness = ResumeFreshness(minimumPause = 30.seconds, timeSource = time)

        freshness.onLeftApp()
        freshness.onPausedInApp()
        time += 2.seconds

        assertFalse(freshness.isCheckNeeded())
    }
}
