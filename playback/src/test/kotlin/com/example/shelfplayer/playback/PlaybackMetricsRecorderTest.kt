package com.example.shelfplayer.playback

import com.example.shelfplayer.core.testing.TestAppClock
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-006 — the two readings, and the distinction the whole thing rests on.
 *
 * `STATE_BUFFERING` means two different things: the first fill of a new item, which is startup latency, and
 * running dry mid-sentence, which is a rebuffer. Conflating them would report a rebuffer on every book that
 * ever played, and would make every buffer preset look equally bad.
 */
class PlaybackMetricsRecorderTest {

    private val clock = TestAppClock()
    private val recorder = PlaybackMetricsRecorder(clock)

    @Test
    fun `nothing has played yet`() {
        assertTrue(recorder.metrics.value.isEmpty)
        assertNull(recorder.metrics.value.lastStartup)
    }

    /** **The one that matters.** A book's first buffer is how long you waited, not a failure. */
    @Test
    fun `the first buffer of an item is startup latency and not a rebuffer`() {
        recorder.onItemPrepared()
        recorder.onBuffering()
        clock.advanceBy(1_400.milliseconds)
        recorder.onReady()

        assertEquals(0, recorder.metrics.value.rebuffers)
        assertEquals(1_400.milliseconds, recorder.metrics.value.lastStartup)
    }

    /** And buffering after it has played once is the thing a bigger buffer is meant to prevent. */
    @Test
    fun `buffering after the item has played counts as a rebuffer`() {
        recorder.onItemPrepared()
        recorder.onReady()

        recorder.onBuffering()
        recorder.onBuffering()

        assertEquals(2, recorder.metrics.value.rebuffers)
    }

    /**
     * `STATE_READY` arrives again after every rebuffer. Treating those as starts would fill the screen with
     * fast "startups" that are really recoveries, hiding the slow first load somebody is trying to see.
     */
    @Test
    fun `recovering from a rebuffer is not a new startup`() {
        recorder.onItemPrepared()
        clock.advanceBy(2.seconds)
        recorder.onReady()
        recorder.onBuffering()
        clock.advanceBy(5.seconds)
        recorder.onReady()

        assertEquals(1, recorder.metrics.value.startupsMeasured)
        assertEquals(2.seconds, recorder.metrics.value.lastStartup, "still the first load's figure")
    }

    /** Switching to another book is starting again: the wait to hear a book is the wait for *that* book. */
    @Test
    fun `each item is measured separately`() {
        recorder.onItemPrepared()
        clock.advanceBy(1.seconds)
        recorder.onReady()

        recorder.onItemPrepared()
        clock.advanceBy(4.seconds)
        recorder.onReady()

        assertEquals(2, recorder.metrics.value.startupsMeasured)
        assertEquals(4.seconds, recorder.metrics.value.lastStartup)
    }

    /** The worst of the session, because an average hides the one that took eleven seconds. */
    @Test
    fun `the slowest start is kept even after a fast one`() {
        recorder.onItemPrepared()
        clock.advanceBy(9.seconds)
        recorder.onReady()

        recorder.onItemPrepared()
        clock.advanceBy(1.seconds)
        recorder.onReady()

        assertEquals(1.seconds, recorder.metrics.value.lastStartup)
        assertEquals(9.seconds, recorder.metrics.value.slowestStartup)
    }

    /** A rebuffer before anything has been ready is impossible, and must not be counted if it arrives. */
    @Test
    fun `buffering before an item was ever ready is ignored`() {
        recorder.onItemPrepared()
        recorder.onBuffering()

        assertEquals(0, recorder.metrics.value.rebuffers)
    }

    /**
     * The session's counts survive the player being released, because the screen reports the *session*.
     * What does not survive is the half-finished stopwatch, which would otherwise measure the gap between
     * two unrelated books.
     */
    @Test
    fun `releasing the player keeps the counts and drops the stopwatch`() {
        recorder.onItemPrepared()
        clock.advanceBy(3.seconds)
        recorder.onReady()
        recorder.onBuffering()

        recorder.onReleased()
        clock.advanceBy(1.seconds)
        recorder.onReady()

        assertEquals(1, recorder.metrics.value.rebuffers, "the count stands")
        assertEquals(1, recorder.metrics.value.startupsMeasured, "and no phantom startup was recorded")
        assertEquals(3.seconds, recorder.metrics.value.lastStartup)
    }
}
