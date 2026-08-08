package com.example.shelfplayer.domain.playback

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-004 — listening time is elapsed playing time, not a position delta.
 *
 * Every reading here is a monotonic one, as the production caller's is.
 */
class ListenedTimeTest {

    @Test
    fun `nothing is counted before playback starts`() {
        val listened = ListenedTime()

        assertEquals(Duration.ZERO, listened.totalAt(30.seconds))
    }

    @Test
    fun `a closed interval is counted once`() {
        val listened = ListenedTime()

        listened.onPlayingChanged(isPlaying = true, at = 10.seconds)
        listened.onPlayingChanged(isPlaying = false, at = 70.seconds)

        assertEquals(60.seconds, listened.total)
    }

    /** A sync mid-chapter must see the seconds since the last transition, not only the closed intervals. */
    @Test
    fun `an interval still in progress is included in the reading`() {
        val listened = ListenedTime()

        listened.onPlayingChanged(isPlaying = true, at = 10.seconds)

        assertEquals(20.seconds, listened.totalAt(30.seconds))
        // Reading it does not close the interval: the next reading keeps counting from the same start.
        assertEquals(50.seconds, listened.totalAt(60.seconds))
    }

    /**
     * Media3 reports `isPlaying` on more events than transitions.
     *
     * A repeated `true` must not restart the interval, or every event during playback would discard the time
     * before it and a book played for an hour would report a few seconds.
     */
    @Test
    fun `a repeated playing event does not restart the interval`() {
        val listened = ListenedTime()

        listened.onPlayingChanged(isPlaying = true, at = 10.seconds)
        listened.onPlayingChanged(isPlaying = true, at = 40.seconds)
        listened.onPlayingChanged(isPlaying = false, at = 70.seconds)

        assertEquals(60.seconds, listened.total)
    }

    /** And a repeated `false` must not close an interval twice, which would double-count it. */
    @Test
    fun `a repeated paused event does not count twice`() {
        val listened = ListenedTime()

        listened.onPlayingChanged(isPlaying = true, at = 10.seconds)
        listened.onPlayingChanged(isPlaying = false, at = 40.seconds)
        listened.onPlayingChanged(isPlaying = false, at = 70.seconds)

        assertEquals(30.seconds, listened.total)
    }

    /** Pausing stops the clock. This is the whole reason the counter is not a wall-clock subtraction. */
    @Test
    fun `time while paused is not counted`() {
        val listened = ListenedTime()

        listened.onPlayingChanged(isPlaying = true, at = 0.seconds)
        listened.onPlayingChanged(isPlaying = false, at = 30.seconds)
        listened.onPlayingChanged(isPlaying = true, at = 600.seconds)
        listened.onPlayingChanged(isPlaying = false, at = 630.seconds)

        assertEquals(60.seconds, listened.total)
    }

    /** [ListenedTime.drain] answers "since the last time you asked", which is what a sync sends. */
    @Test
    fun `draining reports the delta since the previous drain`() {
        val listened = ListenedTime()
        listened.onPlayingChanged(isPlaying = true, at = 0.seconds)

        assertEquals(30.seconds, listened.drain(30.seconds))
        assertEquals(30.seconds, listened.drain(60.seconds))
        assertEquals(Duration.ZERO, listened.drain(60.seconds))
    }

    /** A drain over an interval that was paused throughout reports nothing rather than the wall clock. */
    @Test
    fun `draining across a pause reports only what played`() {
        val listened = ListenedTime()
        listened.onPlayingChanged(isPlaying = true, at = 0.seconds)
        listened.onPlayingChanged(isPlaying = false, at = 20.seconds)

        assertEquals(20.seconds, listened.drain(300.seconds))
        assertEquals(Duration.ZERO, listened.drain(600.seconds))
    }

    /** A new session starts from nothing, so one book's listening cannot be attributed to the next. */
    @Test
    fun `resetting clears the accumulated total`() {
        val listened = ListenedTime()
        listened.onPlayingChanged(isPlaying = true, at = 0.seconds)
        listened.onPlayingChanged(isPlaying = false, at = 60.seconds)

        listened.reset(at = 60.seconds)

        assertEquals(Duration.ZERO, listened.total)
        assertEquals(Duration.ZERO, listened.drain(90.seconds))
    }

    /**
     * A book that runs straight into the next does so without a pause.
     *
     * The reset re-bases the interval in progress rather than dropping it, so the new session starts counting
     * from the reset instead of from whenever the player last reported a transition.
     */
    @Test
    fun `resetting while playing keeps counting from the reset`() {
        val listened = ListenedTime()
        listened.onPlayingChanged(isPlaying = true, at = 0.seconds)

        listened.reset(at = 100.seconds)

        assertEquals(20.seconds, listened.totalAt(120.seconds))
    }

    /**
     * A monotonic clock does not go backwards, but a reading taken on another thread can arrive out of order.
     *
     * Clamping at zero means the worst case is a lost interval rather than a negative listening time, which a
     * server would either reject or subtract.
     */
    @Test
    fun `a reading that appears to go backwards contributes nothing`() {
        val listened = ListenedTime()
        listened.onPlayingChanged(isPlaying = true, at = 100.seconds)

        listened.onPlayingChanged(isPlaying = false, at = 90.seconds)

        assertEquals(Duration.ZERO, listened.total)
    }
}
