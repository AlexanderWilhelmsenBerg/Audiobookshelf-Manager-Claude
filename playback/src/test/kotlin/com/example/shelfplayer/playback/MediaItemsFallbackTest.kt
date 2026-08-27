package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.PlayableTrack
import com.example.shelfplayer.core.model.library.PlaybackSession
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-003 / 22.4, ADR-0016, `docs/risks.md` R-61 — a book whose timeline is not the book.
 *
 * ### The defect, in the numbers the register used
 *
 * A track reporting no duration makes `BookMediaSourceFactory` fall back to the plain factory, which plays
 * the item's URI — the *first* track. The player's timeline is then that one file, while `queueFor` was
 * still handing it `session.startAt`, a whole-book position. A 34-hour book resumed at 4 hours seeked past
 * the end of a 20-minute file, and then every journal tick wrote that file-relative position into the book's
 * stored progress. Product priority 2 is *do not lose progress*, and it was being rewritten silently.
 *
 * These are the containment half. `TrackDurationsTest` is the half that makes the fallback rare in the first
 * place, by computing the missing length from the server's own total.
 *
 * Robolectric because `MediaItem` and `Bundle` are Android types; nothing here touches a player.
 */
@RunWith(RobolectricTestRunner::class)
class MediaItemsFallbackTest {

    // ------------------------------------------------- recovery, end to end through the built item

    /**
     * The ordinary repair, observed where it matters: in the extras the factory will read.
     *
     * The middle track reports nothing and the book is 34 hours, so the hole is 34h − (20h + 10h) = 4h.
     * With that filled in, every duration is positive, the factory concatenates, and the timeline is the
     * book — so nothing else in this file applies.
     */
    @Test
    fun `a single unknown track is repaired in the item's extras`() {
        val item = MediaItems.queueFor(
            session(
                total = 34.hours,
                tracks = listOf(
                    track(1, 20.hours),
                    track(2, Duration.ZERO),
                    track(3, 10.hours),
                ),
            ),
        ).item

        assertEquals(
            listOf(20.hours, 4.hours, 10.hours),
            MediaItems.tracksOf(item).map { it.duration },
        )
        assertFalse(MediaItems.isSingleFileFallback(item), "a repaired book is one timeline window")
    }

    /** And the resume point survives a repair, because the timeline is still the book. */
    @Test
    fun `a repaired book keeps its resume position`() {
        val queue = MediaItems.queueFor(
            session(
                total = 34.hours,
                startAt = 4.hours,
                tracks = listOf(track(1, 20.hours), track(2, Duration.ZERO), track(3, 10.hours)),
            ),
        )

        assertEquals(4.hours.inWholeMilliseconds, queue.startPositionMs)
    }

    // ------------------------------------------------- containment, when recovery is refused

    /**
     * **The data loss, closed.** Two unknown tracks cannot be recovered, so this book will play one file —
     * and its start position is zero rather than four hours.
     *
     * Four hours into a 20-hour first file is a legal seek and a wrong one; into a 20-*minute* file it is
     * past the end. Either way the position no longer means what the writers think it means, and zero is
     * the only value that means the same thing in both coordinate spaces.
     */
    @Test
    fun `an unrecoverable book starts at zero rather than at the book position`() {
        val queue = MediaItems.queueFor(
            session(
                total = 34.hours,
                startAt = 4.hours,
                tracks = listOf(
                    track(1, 20.minutes),
                    track(2, Duration.ZERO),
                    track(3, Duration.ZERO),
                ),
            ),
        )

        assertEquals(0L, queue.startPositionMs)
        assertTrue(MediaItems.isSingleFileFallback(queue.item), "the writers have to be able to see this")
    }

    /**
     * The same for the refusal that PRODUCT_SPEC 22.4 requires: an excluded track present alongside an
     * unknown one.
     *
     * Recovery is declined because no capture settles whether the session total counts the excluded file,
     * so this book is contained rather than repaired from a guess.
     */
    @Test
    fun `an excluded track alongside an unknown one is contained rather than guessed`() {
        val queue = MediaItems.queueFor(
            session(
                total = 34.hours,
                startAt = 4.hours,
                tracks = listOf(
                    track(1, 20.hours),
                    track(2, Duration.ZERO),
                    track(3, 10.hours, isExcluded = true),
                ),
            ),
        )

        assertEquals(0L, queue.startPositionMs)
        assertTrue(MediaItems.isSingleFileFallback(queue.item))
    }

    // ------------------------------------------------- the predicate's own edges

    /** An ordinary book is not a fallback, which is the case every other test in the suite relies on. */
    @Test
    fun `a complete book is not a fallback`() {
        val item = MediaItems.queueFor(session()).item

        assertFalse(MediaItems.isSingleFileFallback(item))
    }

    /**
     * **An item with no track list is not reported as a fallback**, and that is deliberate.
     *
     * Such an item is not one this app built, or is one whose extras did not survive. The honest answer is
     * "unknown", and reporting it as degraded would stop the journal writing for it — a new way to lose
     * progress introduced by a fix for losing progress. `docs/risks.md` R-61 records the choice.
     */
    @Test
    fun `an item with no tracks is not reported as a fallback`() {
        val bare = androidx.media3.common.MediaItem.Builder().setMediaId("tidewatch").build()

        assertFalse(MediaItems.isSingleFileFallback(bare))
        assertTrue(MediaItems.tracksOf(bare).isEmpty(), "the premise: this item carries no track list")
    }

    private companion object {
        val OWNER = ProfileId("prf_ada")
        val BOOK = LibraryItemId("tidewatch")

        fun track(index: Int, duration: Duration, isExcluded: Boolean = false) = PlayableTrack(
            index = index,
            url = "https://books.example/api/items/tidewatch/file/$index",
            // Not read by anything under test — ADR-0016 deleted the per-file arithmetic that used it.
            startOffset = Duration.ZERO,
            duration = duration,
            mimeType = "audio/mpeg",
            isExcluded = isExcluded,
        )

        fun session(
            total: Duration = 10.seconds,
            startAt: Duration = Duration.ZERO,
            tracks: List<PlayableTrack> = listOf(track(1, 6.seconds), track(2, 4.seconds)),
        ) = PlaybackSession(
            profileId = OWNER,
            id = "session-1",
            bookId = BOOK,
            title = "The Tidewatch Cycle",
            author = "Marisol Holt",
            coverUrl = null,
            startAt = startAt,
            duration = total,
            tracks = tracks,
            chapters = emptyList(),
        )
    }
}
