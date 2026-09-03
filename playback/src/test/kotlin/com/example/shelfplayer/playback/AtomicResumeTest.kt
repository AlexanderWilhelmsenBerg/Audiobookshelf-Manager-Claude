package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.LibraryItemId
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC SYNC-002 — adopting another device's position, and the order that makes it one thing.
 *
 * ### The device run this file exists for
 *
 * A log recorded a complete adoption and the listener heard none of it. The history pane carried *"moved
 * on another device, 9:59 → 15:58"*, the freshness check had answered `Ahead`, the resume had run — and
 * the next interval sync reported 611177ms, which is 10:11. The audio never left 9:59.
 *
 * The first fix read `MediaController.currentPosition` back a second later to catch that. It cannot: the
 * controller is an IPC proxy that masks a seek locally the instant it is asked, so a seek the player
 * dropped and a seek it honoured produce the same answer from the same object. Re-reading the thing that
 * told you the lie is not a check.
 *
 * So the operation moved to `PlaybackService`, which owns the `ExoPlayer` and can therefore ask *the
 * player* where it went. These are the four steps of that operation and the three ways it can refuse.
 *
 * ### Why a fake and not a real player
 *
 * `ExoPlayer` cannot be constructed in a unit test and `MediaController` is final, which is how the
 * original two-coroutine defect survived review twice. [ResumeTarget] is the seam that makes the ordering
 * a pure function, so the assertion below is the *sequence itself* rather than a comment claiming it.
 */
class AtomicResumeTest {

    /**
     * **The acceptance case: seek to 10:39:02, confirm the player got there, and only then play.**
     *
     * The order is what is being asserted, not the outcome. A `play` before the confirmation is audio from
     * the position the listener was trying to leave, which is the whole defect.
     */
    @Test
    fun `the adopted position is sought, confirmed, and only then played`() = runTest {
        val player = FakeTarget(loaded = BOOK, landsAt = MOVED_TO)

        val outcome = player.resume(MOVED_TO)

        assertEquals(ResumeOutcome.Resumed, outcome)
        assertEquals(listOf("seekTo(37142000)", "awaited", "play"), player.commands)
    }

    /** An idle player is prepared first: a seek on one is silently discarded, so it cannot come after. */
    @Test
    fun `an idle player is prepared before the seek`() = runTest {
        val player = FakeTarget(loaded = BOOK, landsAt = MOVED_TO, needsPreparing = true)

        assertEquals(ResumeOutcome.Resumed, player.resume(MOVED_TO))
        assertEquals(listOf("prepare", "seekTo(37142000)", "awaited", "play"), player.commands)
    }

    // ------------------------------------------- the three refusals, and none of them plays

    /**
     * **The reported failure: the seek did not arrive, so nothing plays.**
     *
     * The player reports itself still near where it started. Playing from there is exactly what the device
     * run heard, and the answer that goes back to the controller is what lets it reopen the book instead.
     */
    @Test
    fun `a seek that landed somewhere else does not play`() = runTest {
        val player = FakeTarget(loaded = BOOK, landsAt = PAUSED_AT)

        val outcome = player.resume(MOVED_TO)

        assertEquals(ResumeOutcome.SeekLost, outcome)
        assertEquals(listOf("seekTo(37142000)", "awaited"), player.commands, "no play may follow a lost seek")
    }

    /** A player that never reports back at all is the same refusal: unverified is not resumed. */
    @Test
    fun `a seek the player never reports does not play`() = runTest {
        val player = FakeTarget(loaded = BOOK, landsAt = null)

        assertEquals(ResumeOutcome.SeekLost, player.resume(MOVED_TO))
        assertEquals(listOf("seekTo(37142000)", "awaited"), player.commands)
    }

    /**
     * A Play that raced a book change touches nothing.
     *
     * The controller cannot check this for itself — by the time its answer came back the queue could have
     * changed again — so the book travels with the command and the service is what refuses. Without it,
     * the *new* book would be seeked to the old one's position.
     */
    @Test
    fun `a different loaded book is refused before anything is touched`() = runTest {
        val player = FakeTarget(loaded = OTHER_BOOK, landsAt = MOVED_TO)

        assertEquals(ResumeOutcome.WrongBook, player.resume(MOVED_TO))
        assertEquals(emptyList(), player.commands)
    }

    /** And a player holding nothing has nothing to seek. */
    @Test
    fun `an empty player is refused`() = runTest {
        val player = FakeTarget(loaded = null, landsAt = MOVED_TO)

        assertEquals(ResumeOutcome.NotLoaded, player.resume(MOVED_TO))
        assertEquals(emptyList(), player.commands)
    }

    // ------------------------------------------- the tolerance, in both directions

    /**
     * A seek that landed a few hundred milliseconds off counts.
     *
     * `ExoPlayer` seeks to the nearest sync sample unless the extractor can do better, so an exact landing
     * is not something a seek promises. The tolerance has to be wide enough for that and narrow enough
     * that the reported failure — landing five minutes away — is never inside it.
     */
    @Test
    fun `a landing inside the tolerance counts as adopted`() = runTest {
        val player = FakeTarget(loaded = BOOK, landsAt = MOVED_TO - 400.milliseconds)

        assertEquals(ResumeOutcome.Resumed, player.resume(MOVED_TO))
    }

    /**
     * **A remote rewind reads the same way round**, which a magnitude comparison would get wrong.
     *
     * Somebody re-listening to a chapter on the web leaves the server *behind* this device. Landing on the
     * smaller number is the resume having worked.
     */
    @Test
    fun `a rewind that landed counts as adopted`() = runTest {
        val player = FakeTarget(loaded = BOOK, landsAt = PAUSED_AT)

        assertEquals(ResumeOutcome.Resumed, player.resume(PAUSED_AT))
        assertEquals(listOf("seekTo(22461280)", "awaited", "play"), player.commands)
    }

    private suspend fun FakeTarget.resume(target: Duration): ResumeOutcome = seekAndResume(
        bookId = BOOK,
        target = target,
        tolerance = 1.seconds,
        timeout = 2.seconds,
    )

    /**
     * A player that records what it was asked to do, in order, and lands where it is told.
     *
     * A list of strings rather than a sealed hierarchy: the assertion is about *sequence*, and the
     * shortest thing that shows a wrong sequence in a failure message is the sequence itself. `awaited`
     * appears where the real implementation waits for the player's own discontinuity, so a `play` that
     * came before the confirmation is visible as an ordering rather than inferred from an outcome.
     */
    private class FakeTarget(
        private val loaded: LibraryItemId?,
        /** Where the player says it ended up, or `null` for a player that never reports back. */
        private val landsAt: Duration?,
        private val needsPreparing: Boolean = false,
    ) : ResumeTarget {
        val commands = mutableListOf<String>()

        override fun loadedBookId(): LibraryItemId? = loaded

        override fun needsPreparing(): Boolean = needsPreparing

        override fun prepare() {
            commands += "prepare"
        }

        override suspend fun seekAndAwait(position: Duration, timeout: Duration): Duration? {
            commands += "seekTo(${position.inWholeMilliseconds})"
            commands += "awaited"
            return landsAt
        }

        override fun play() {
            commands += "play"
        }
    }

    private companion object {
        val BOOK = LibraryItemId("book-voyage-1")
        val OTHER_BOOK = LibraryItemId("book-voyage-2")

        /** 6:14:21, where the device log's listener paused. */
        val PAUSED_AT: Duration = 22_461_280.milliseconds

        /** 10:39:02, where the web player took the book. */
        val MOVED_TO: Duration = 37_142_000.milliseconds
    }
}
