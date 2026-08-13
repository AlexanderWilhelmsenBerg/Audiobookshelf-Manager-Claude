package com.example.shelfplayer.domain.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.playback.AutoRewind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-009 — the rewind's arithmetic and its floor.
 *
 * The bucket boundaries are tested at their edges because "2–10 minutes" is ambiguous in prose and exact in
 * code, and the clamp is tested because a rewind that crosses a chapter boundary is the one failure mode
 * this feature can have that a listener would report as a position bug rather than as a setting.
 */
class AutoRewindMathTest {

    private val enabled = AutoRewind.Default.copy(isEnabled = true)

    private val chapters = listOf(
        chapter(index = 0, start = Duration.ZERO, end = 10.minutes),
        chapter(index = 1, start = 10.minutes, end = 20.minutes),
    )

    /** PLAY-009: "disabled by default", and disabled means the position does not move at all. */
    @Test
    fun `a disabled setting never moves the position`() {
        val resumed = AutoRewindMath.resumeAt(
            from = 15.minutes,
            pausedFor = 3.hoursAsMinutes(),
            settings = AutoRewind.Default,
        )

        assertEquals(15.minutes, resumed)
    }

    /** The requirement's own example: under two minutes rewinds nothing. */
    @Test
    fun `a short pause rewinds nothing`() {
        assertEquals(
            15.minutes,
            AutoRewindMath.resumeAt(from = 15.minutes, pausedFor = 90.seconds, settings = enabled),
        )
    }

    /**
     * The band edges, which prose leaves ambiguous.
     *
     * Two minutes exactly is the *medium* band: "under 2 minutes: 0 seconds" makes two minutes the first
     * value that is not under it.
     */
    @Test
    fun `the band boundaries fall on the later band`() {
        assertEquals(Duration.ZERO, enabled.amountFor(AutoRewind.ShortPause - 1.seconds))
        assertEquals(5.seconds, enabled.amountFor(AutoRewind.ShortPause))
        assertEquals(5.seconds, enabled.amountFor(AutoRewind.MediumPause - 1.seconds))
        assertEquals(15.seconds, enabled.amountFor(AutoRewind.MediumPause))
        assertEquals(15.seconds, enabled.amountFor(AutoRewind.LongPause - 1.seconds))
        assertEquals(30.seconds, enabled.amountFor(AutoRewind.LongPause))
    }

    @Test
    fun `a long pause rewinds the long amount`() {
        assertEquals(
            15.minutes - 30.seconds,
            AutoRewindMath.resumeAt(from = 15.minutes, pausedFor = 2.hoursAsMinutes(), settings = enabled),
        )
    }

    /**
     * PLAY-009: "rewind cannot move before chapter/book start".
     *
     * Paused ten seconds into chapter two, a thirty-second rewind would land in chapter one. It stops at
     * the boundary instead.
     */
    @Test
    fun `a rewind stops at the start of the current chapter`() {
        val resumed = AutoRewindMath.resumeAt(
            from = 10.minutes + 10.seconds,
            pausedFor = 2.hoursAsMinutes(),
            settings = enabled,
            chapters = chapters,
        )

        assertEquals(10.minutes, resumed)
    }

    /** With no chapters the book's start is the only floor there is. */
    @Test
    fun `a rewind near the start of a book without chapters stops at zero`() {
        val resumed = AutoRewindMath.resumeAt(
            from = 10.seconds,
            pausedFor = 2.hoursAsMinutes(),
            settings = enabled,
        )

        assertEquals(Duration.ZERO, resumed)
    }

    @Test
    fun `a rewind well inside a chapter is not clamped`() {
        val resumed = AutoRewindMath.resumeAt(
            from = 15.minutes,
            pausedFor = 2.hoursAsMinutes(),
            settings = enabled,
            chapters = chapters,
        )

        assertEquals(15.minutes - 30.seconds, resumed)
    }

    /**
     * Malformed metadata must not move a listener *forwards*.
     *
     * A chapter whose start is after the position is nonsense, and the honest response is to leave the
     * position alone rather than to obey the floor.
     */
    @Test
    fun `a chapter starting after the position cannot push the listener forward`() {
        val broken = listOf(chapter(index = 0, start = 30.minutes, end = 40.minutes))

        val resumed = AutoRewindMath.resumeAt(
            from = 5.minutes,
            pausedFor = 2.hoursAsMinutes(),
            settings = enabled,
            chapters = broken,
        )

        assertEquals(5.minutes, resumed)
    }

    /** The undo amount is derived from what actually happened, not from what was asked for. */
    @Test
    fun `the applied amount reflects the clamp`() {
        val from = 10.minutes + 10.seconds
        val resumed = AutoRewindMath.resumeAt(
            from = from,
            pausedFor = 2.hoursAsMinutes(),
            settings = enabled,
            chapters = chapters,
        )

        assertEquals(10.seconds, AutoRewindMath.appliedAmount(from, resumed))
    }

    @Test
    fun `the applied amount is zero when nothing moved`() {
        assertEquals(Duration.ZERO, AutoRewindMath.appliedAmount(5.minutes, 5.minutes))
    }

    private fun Int.hoursAsMinutes(): Duration = (this * 60).minutes

    private fun chapter(index: Int, start: Duration, end: Duration) = Chapter(
        serverId = ServerId("server-1"),
        bookId = LibraryItemId("book-1"),
        index = index,
        title = "Chapter ${index + 1}",
        start = start,
        end = end,
    )
}
