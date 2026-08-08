package com.example.shelfplayer.domain.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-008 — the sleep timer's arithmetic, at every edge it has.
 *
 * These are the cases a device test cannot reach: a fade that ends a second early, a chapter list whose
 * tags are wrong, a restart on the last chapter of a book. Each one is a bad night's listening rather
 * than a crash, which is exactly the kind of bug that survives manual testing.
 */
class SleepTimerMathTest {

    // --- Fixed timers ------------------------------------------------------------------------------

    @Test
    fun `a fixed timer counts down from its deadline`() {
        assertEquals(
            20.minutes,
            SleepTimerMath.remainingUntil(deadline = 30.minutes, elapsed = 10.minutes),
        )
    }

    /**
     * A timer past its deadline is done, not overdue.
     *
     * Reachable whenever the ticker is late — a device that dozed, a coroutine that did not run for a
     * few seconds — and the alternative is a notification reading "-3 min".
     */
    @Test
    fun `a fixed timer past its deadline reads as zero, never negative`() {
        assertEquals(
            Duration.ZERO,
            SleepTimerMath.remainingUntil(deadline = 30.minutes, elapsed = 33.minutes),
        )
    }

    // --- End of chapter ----------------------------------------------------------------------------

    @Test
    fun `an end-of-chapter timer measures to the current chapter's end`() {
        val remaining = SleepTimerMath.remainingToChapterEnd(CHAPTERS, position = 4.minutes)

        assertEquals(6.minutes, remaining)
    }

    /**
     * Restarting an end-of-chapter timer has to reach the chapter *after* this one.
     *
     * Restarting to the current chapter's end would be a shake that changes nothing, which reads as the
     * feature being broken rather than as a deliberate no-op.
     */
    @Test
    fun `skipping one boundary measures to the end of the next chapter`() {
        val remaining = SleepTimerMath.remainingToChapterEnd(CHAPTERS, position = 4.minutes, skip = 1)

        assertEquals(16.minutes, remaining)
    }

    /** PLAY-008 — "handles ... absent chapters gracefully". A book with none simply cannot do this. */
    @Test
    fun `a book with no chapters has no end-of-chapter`() {
        assertNull(SleepTimerMath.remainingToChapterEnd(emptyList(), position = 4.minutes))
    }

    /**
     * PLAY-008 — "handles malformed ... chapters gracefully".
     *
     * A chapter whose end is at or before its start comes from a bad tag, and it must not produce a
     * timer of zero or of a negative length. It is skipped, and the next usable boundary is used.
     */
    @Test
    fun `a chapter that ends before it starts is skipped rather than used`() {
        val malformed = listOf(
            chapter(0, "Broken", start = 0.minutes, end = 0.minutes),
            chapter(1, "Fine", start = 0.minutes, end = 10.minutes),
        )

        // Eight minutes: the usable chapter ends at ten, and the listener is two minutes in.
        assertEquals(8.minutes, SleepTimerMath.remainingToChapterEnd(malformed, position = 2.minutes))
    }

    /** Past the last boundary there is nothing left to stop at, and saying so beats inventing one. */
    @Test
    fun `a position past every chapter has no end-of-chapter`() {
        assertNull(SleepTimerMath.remainingToChapterEnd(CHAPTERS, position = 30.minutes))
    }

    @Test
    fun `skipping past the last chapter has no end-of-chapter`() {
        assertNull(SleepTimerMath.remainingToChapterEnd(CHAPTERS, position = 4.minutes, skip = 5))
    }

    /** Chapters arrive in server order, and a server that sent them unordered must not change the answer. */
    @Test
    fun `chapters are read in time order rather than list order`() {
        val shuffled = CHAPTERS.reversed()

        assertEquals(
            SleepTimerMath.remainingToChapterEnd(CHAPTERS, position = 4.minutes),
            SleepTimerMath.remainingToChapterEnd(shuffled, position = 4.minutes),
        )
    }

    // --- The fade ----------------------------------------------------------------------------------

    @Test
    fun `volume is full until the fade window opens`() {
        assertEquals(1f, SleepTimerMath.fadeVolume(remaining = 60.seconds, fade = 10.seconds))
        assertEquals(1f, SleepTimerMath.fadeVolume(remaining = 10.seconds, fade = 10.seconds))
    }

    @Test
    fun `volume ramps down through the fade window`() {
        assertEquals(0.5f, SleepTimerMath.fadeVolume(remaining = 5.seconds, fade = 10.seconds))
        assertEquals(0.1f, SleepTimerMath.fadeVolume(remaining = 1.seconds, fade = 10.seconds), 0.001f)
    }

    @Test
    fun `volume is silent at zero and never below it`() {
        assertEquals(0f, SleepTimerMath.fadeVolume(remaining = Duration.ZERO, fade = 10.seconds))
        assertEquals(0f, SleepTimerMath.fadeVolume(remaining = (-5).seconds, fade = 10.seconds))
    }

    /** No fade configured means full volume right up to the pause, which is the honest reading of zero. */
    @Test
    fun `a fade of zero never lowers the volume`() {
        assertEquals(1f, SleepTimerMath.fadeVolume(remaining = 1.seconds, fade = Duration.ZERO))
    }

    // --- Restart length ----------------------------------------------------------------------------

    @Test
    fun `a fixed timer restarts to its own length`() {
        assertEquals(45.minutes, SleepTimerMath.restartLength(SleepTimerMode.Fixed(45.minutes)))
    }

    @Test
    fun `an end-of-chapter timer has no length to restart to`() {
        assertNull(SleepTimerMath.restartLength(SleepTimerMode.EndOfChapter))
    }

    private fun assertEquals(expected: Float, actual: Float, tolerance: Float) {
        kotlin.test.assertTrue(
            kotlin.math.abs(expected - actual) <= tolerance,
            "expected $expected but was $actual",
        )
    }

    private companion object {
        val BOOK = LibraryItemId("book-1")
        val SERVER = ServerId("server-1")

        /** Ten minutes each, so a skip is unambiguous. */
        val CHAPTERS = listOf(
            chapter(0, "One", start = 0.minutes, end = 10.minutes),
            chapter(1, "Two", start = 10.minutes, end = 20.minutes),
        )

        fun chapter(index: Int, title: String, start: Duration, end: Duration) = Chapter(
            serverId = SERVER,
            bookId = BOOK,
            index = index,
            title = title,
            start = start,
            end = end,
        )
    }
}
