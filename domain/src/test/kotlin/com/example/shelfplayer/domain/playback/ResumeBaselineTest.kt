package com.example.shelfplayer.domain.playback

import com.example.shelfplayer.core.model.LibraryItemId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC SYNC-002 — the state machine the freshness check decides from.
 *
 * ### Why the tests are worth more here than anywhere else on this path
 *
 * Five device runs on one symptom, and the reason each fix failed was the same: the app was deciding "has
 * another device moved this book" from evidence that could not answer it. This class is the fourth attempt
 * at the *evidence*, not the fifth attempt at a comparison — so what has to be pinned is not an outcome but
 * the exact set of circumstances under which the app is willing to claim an agreement with the server.
 *
 * The cases below are that set. Everything not in it answers `null`, which means *resume locally*, which
 * is the direction that cannot lose a position.
 */
class ResumeBaselineTest {

    /** **The acceptance case:** paused, the server took it, and the pause is now a usable fact. */
    @Test
    fun `a pause the server accepted becomes an acknowledged baseline`() {
        val baseline = ResumeBaseline()

        val generation = baseline.onPaused(BOOK, PAUSED_AT)
        assertNull(baseline.acknowledged(BOOK), "a pending pause is a claim, not yet a fact")

        assertTrue(baseline.onPositionAccepted(BOOK, PAUSED_AT, generation))

        val acknowledged = assertNotNull(baseline.acknowledged(BOOK))
        assertEquals(PAUSED_AT, acknowledged.position)
        assertEquals(BOOK, acknowledged.bookId)
        assertEquals(generation, acknowledged.generation)
    }

    /**
     * **A pause whose sync never came back is not a baseline**, and that is the ambiguous case named in the
     * requirement.
     *
     * The server might be holding this position or something older, and nothing local can tell those
     * apart. Preserving the local position is the only answer that cannot throw one away.
     */
    @Test
    fun `a pause with no acknowledgement is not a baseline`() {
        val baseline = ResumeBaseline()

        baseline.onPaused(BOOK, PAUSED_AT)

        assertNull(baseline.acknowledged(BOOK))
    }

    /** Nothing has paused at all — every resume after a cold start, and every one from a car or a headset. */
    @Test
    fun `nothing paused is not a baseline`() {
        assertNull(ResumeBaseline().acknowledged(BOOK))
    }

    // ------------------------------------------- what revokes an acknowledgement

    /**
     * **A local seek revokes it.** The acknowledged position described where this device was at rest.
     *
     * After a seek the server holds something different for a reason that has nothing to do with another
     * device, and comparing against it would adopt the server's number over the listener's own choice.
     */
    @Test
    fun `a local move drops an acknowledged baseline`() {
        val baseline = ResumeBaseline()
        val generation = baseline.onPaused(BOOK, PAUSED_AT)
        baseline.onPositionAccepted(BOOK, PAUSED_AT, generation)

        baseline.onLocalMove()

        assertNull(baseline.acknowledged(BOOK))
    }

    /** And a track or book boundary, which is the same reasoning one scope wider. */
    @Test
    fun `closing the book drops an acknowledged baseline`() {
        val baseline = ResumeBaseline()
        val generation = baseline.onPaused(BOOK, PAUSED_AT)
        baseline.onPositionAccepted(BOOK, PAUSED_AT, generation)

        baseline.onBookClosed()

        assertNull(baseline.acknowledged(BOOK))
    }

    /** A baseline is per book. Another book's pause is not evidence about this one. */
    @Test
    fun `a baseline is not offered for a different book`() {
        val baseline = ResumeBaseline()
        val generation = baseline.onPaused(BOOK, PAUSED_AT)
        baseline.onPositionAccepted(BOOK, PAUSED_AT, generation)

        assertNull(baseline.acknowledged(OTHER_BOOK))
        assertNotNull(baseline.acknowledged(BOOK))
    }

    // ------------------------------------------- the generation guard

    /**
     * **The race the generation exists for.** The acknowledgement arrives from the network, later than
     * the pause it belongs to, and the listener can seek in between.
     *
     * Without the guard, that answer would promote a record describing a position the player has already
     * left — and, because a paused player syncs the same position every thirty seconds, matching on the
     * position alone cannot tell "this confirms my pause" from "this confirms a pause two seeks ago".
     */
    @Test
    fun `an acknowledgement for a superseded generation is refused`() {
        val baseline = ResumeBaseline()
        val generation = baseline.onPaused(BOOK, PAUSED_AT)

        baseline.onLocalMove()

        assertFalse(baseline.onPositionAccepted(BOOK, PAUSED_AT, generation))
        assertNull(baseline.acknowledged(BOOK))
    }

    /**
     * And it cannot promote the *next* pause either, which is the subtler half.
     *
     * A seek and a second pause put a new record in place. An answer still in flight for the first one
     * carries the old generation, and applying it would mark a pause acknowledged that the server has said
     * nothing about — the exact shape of "acknowledged" being claimed rather than established.
     */
    @Test
    fun `a late acknowledgement cannot promote a later pause`() {
        val baseline = ResumeBaseline()
        val first = baseline.onPaused(BOOK, PAUSED_AT)
        baseline.onLocalMove()
        baseline.onPaused(BOOK, MOVED_TO)

        assertFalse(baseline.onPositionAccepted(BOOK, PAUSED_AT, first))
        assertNull(baseline.acknowledged(BOOK), "the second pause has not been acknowledged by anything")
    }

    /**
     * **And the case the generation is the only thing that catches: back at the same position.**
     *
     * Pause at 6:14:21, seek away, pause back at 6:14:21. The stale answer's *position* matches the record
     * exactly, so nothing but the generation can tell the two pauses apart — and they are not
     * interchangeable: the seek in between uploaded a different position, so the server is no longer
     * holding 6:14:21 at all. Promoting on the stale answer would record an agreement that had been broken
     * and then compare the next Play against it.
     *
     * This is the case that was checked by removing the generation condition and watching this test go
     * red; the two above stay green on the position tolerance alone.
     */
    @Test
    fun `a late acknowledgement cannot promote a second pause at the same position`() {
        val baseline = ResumeBaseline()
        val first = baseline.onPaused(BOOK, PAUSED_AT)
        baseline.onLocalMove()
        baseline.onPaused(BOOK, PAUSED_AT)

        assertFalse(baseline.onPositionAccepted(BOOK, PAUSED_AT, first))
        assertNull(baseline.acknowledged(BOOK), "the server stopped holding this position when we seeked away")
    }

    /** An acknowledgement naming a different book is refused whatever generation it carries. */
    @Test
    fun `an acknowledgement for another book is refused`() {
        val baseline = ResumeBaseline()
        val generation = baseline.onPaused(BOOK, PAUSED_AT)

        assertFalse(baseline.onPositionAccepted(OTHER_BOOK, PAUSED_AT, generation))
        assertNull(baseline.acknowledged(BOOK))
    }

    /** [ResumeBaseline.generationFor] is what the sync reads before it sends. `null` when nothing is held. */
    @Test
    fun `the generation is readable for a pending pause and absent otherwise`() {
        val baseline = ResumeBaseline()

        assertNull(baseline.generationFor(BOOK))

        val generation = baseline.onPaused(BOOK, PAUSED_AT)

        assertEquals(generation, baseline.generationFor(BOOK))
        assertNull(baseline.generationFor(OTHER_BOOK))

        baseline.onLocalMove()

        assertNull(baseline.generationFor(BOOK))
    }

    // ------------------------------------------- the acceptance tolerance

    /**
     * A few milliseconds between the pause and the sync is the same position.
     *
     * Both read the player independently; two consecutive syncs of a *standing* player have been observed
     * three milliseconds apart. Requiring exact equality would have made almost every real pause
     * unacknowledgeable, which is the failure mode that looks like the feature simply never working.
     */
    @Test
    fun `an acknowledgement a few milliseconds off still promotes`() {
        val baseline = ResumeBaseline()
        val generation = baseline.onPaused(BOOK, PAUSED_AT)

        assertTrue(baseline.onPositionAccepted(BOOK, PAUSED_AT + 3.milliseconds, generation))
        assertEquals(PAUSED_AT, baseline.acknowledged(BOOK)?.position, "the pause's own position is kept")
    }

    /**
     * A sync of a genuinely different position does **not** promote this pause.
     *
     * The interval ticker keeps running while a book plays, so a sync can carry a position that has moved
     * on. Promoting on it would record an agreement about a number the pause never held, and the
     * comparison at the next Play would then be against the wrong one.
     */
    @Test
    fun `an acknowledgement of a different position does not promote`() {
        val baseline = ResumeBaseline()
        val generation = baseline.onPaused(BOOK, PAUSED_AT)

        assertFalse(baseline.onPositionAccepted(BOOK, PAUSED_AT + 5.seconds, generation))
        assertNull(baseline.acknowledged(BOOK))
    }

    /**
     * An **interval** sync of the paused position promotes it, which is deliberate.
     *
     * A pause whose own upload failed and whose next thirty-second tick succeeded is just as firmly
     * agreed. Refusing it would throw away a fact the app already holds, and would make the feature depend
     * on the one request most likely to be the one that failed.
     */
    @Test
    fun `a later sync of the same position promotes a pause whose own upload failed`() {
        val baseline = ResumeBaseline()
        val generation = baseline.onPaused(BOOK, PAUSED_AT)

        // The pause's own sync failed: nothing was reported. Thirty seconds later the ticker succeeds.
        assertTrue(baseline.onPositionAccepted(BOOK, PAUSED_AT, generation))
        assertNotNull(baseline.acknowledged(BOOK))
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
