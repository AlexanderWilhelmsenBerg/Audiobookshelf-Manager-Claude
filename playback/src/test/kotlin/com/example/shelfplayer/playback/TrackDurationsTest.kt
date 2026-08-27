package com.example.shelfplayer.playback

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-003 / 22.4, `docs/risks.md` R-61 — recovering a track length the server did not give.
 *
 * The defect: a track reporting zero made the book fall back to playing one file, and the fallback switched
 * coordinate spaces without telling anything downstream, so every journal tick wrote a file offset into the
 * book's stored progress. These cover the half that makes the fallback rare; `MediaItemsFallbackTest` covers
 * the half that makes what remains safe.
 */
class TrackDurationsTest {

    // ------------------------------------------------------------------ what it recovers

    /**
     * The case worth having: one unknown track, and the server's total says exactly how long it is.
     *
     * The identity this rests on is captured, not assumed — a session's `duration` equals the sum of its
     * `audioTracks` durations, pinned by `PlaybackSessionDurationTest` against the two play fixtures.
     */
    @Test
    fun `a single unknown track is recovered from the book total`() {
        val recovered = TrackDurations.recovered(
            durations = listOf(1.hours, Duration.ZERO, 30.minutes),
            bookTotal = 2.hours,
            anyExcluded = false,
        )

        assertEquals(listOf(1.hours, 30.minutes, 30.minutes), recovered)
    }

    /** The unknown track can be first or last; the remainder does not care where the hole is. */
    @Test
    fun `the hole can be anywhere in the list`() {
        assertEquals(
            listOf(20.minutes, 40.minutes),
            TrackDurations.recovered(listOf(Duration.ZERO, 40.minutes), 1.hours, anyExcluded = false),
        )
        assertEquals(
            listOf(40.minutes, 20.minutes),
            TrackDurations.recovered(listOf(40.minutes, Duration.ZERO), 1.hours, anyExcluded = false),
        )
    }

    /** Nothing to repair returns the input, so a caller needs one call rather than a pre-check as well. */
    @Test
    fun `a complete list comes back unchanged`() {
        val durations = listOf(1.hours, 30.minutes)

        assertEquals(durations, TrackDurations.recovered(durations, 90.minutes, anyExcluded = false))
    }

    /**
     * A negative duration counts as unknown, not as a length.
     *
     * `PlaybackMapper` maps whatever the server sent through `seconds(...)`, so a negative is reachable from
     * a malformed response, and `ConcatenatingMediaSource2` would reject it later and less legibly.
     */
    @Test
    fun `a negative duration is treated as unknown`() {
        assertEquals(
            listOf(30.minutes, 30.minutes),
            TrackDurations.recovered(listOf((-5).seconds, 30.minutes), 1.hours, anyExcluded = false),
        )
    }

    // ------------------------------------------------------------------ what it refuses, and why

    /**
     * **Two unknown tracks cannot be split**, and guessing would be the same corruption in a quieter form.
     *
     * The remainder is their sum. Halving it, or giving it all to one, produces a timeline whose parts do
     * not match the files behind them — so positions drift within the book rather than being wrong outright,
     * which is harder to notice and no less wrong.
     */
    @Test
    fun `more than one unknown track is refused`() {
        assertNull(
            TrackDurations.recovered(
                durations = listOf(1.hours, Duration.ZERO, Duration.ZERO),
                bookTotal = 2.hours,
                anyExcluded = false,
            ),
        )
    }

    /**
     * **An excluded track is refused because the arithmetic is unpinned, not because it is hard** — this is
     * PRODUCT_SPEC 22.4 in a test.
     *
     * No captured fixture has an excluded track, so whether a session's `duration` counts an excluded file's
     * length is not established. If it does, the remainder is inflated by that file and the recovered track
     * comes out too long. Deleting this guard is exactly the kind of plausible change a capture has to
     * license first.
     */
    @Test
    fun `an excluded track is refused because the total's meaning is not captured`() {
        assertNull(
            TrackDurations.recovered(
                durations = listOf(1.hours, Duration.ZERO),
                bookTotal = 2.hours,
                anyExcluded = true,
            ),
        )
    }

    /** No total, nothing to subtract from. */
    @Test
    fun `a missing book total is refused`() {
        assertNull(TrackDurations.recovered(listOf(1.hours, Duration.ZERO), Duration.ZERO, anyExcluded = false))
    }

    /**
     * A total that is not larger than the known tracks yields nothing positive, so it is refused rather
     * than turned into a zero-length track — which is where this started.
     */
    @Test
    fun `a total that leaves no remainder is refused`() {
        assertNull(TrackDurations.recovered(listOf(1.hours, Duration.ZERO), 1.hours, anyExcluded = false))
        assertNull(TrackDurations.recovered(listOf(2.hours, Duration.ZERO), 1.hours, anyExcluded = false))
    }

    /** An empty list has no hole in it. */
    @Test
    fun `an empty list comes back empty`() {
        assertEquals(emptyList(), TrackDurations.recovered(emptyList(), 1.hours, anyExcluded = false))
    }

    /**
     * Every refusal leaves the fallback reachable, and that is the point of the other half of the fix.
     *
     * `MediaItems.isSingleFileFallback` is what a refused recovery hands over to; if this test's premise
     * ever stops holding — if `recovered` starts returning a repaired list for every input — the
     * containment half becomes dead code and somebody should notice here first.
     */
    @Test
    fun `a refusal leaves the list unrepaired for the containment half to catch`() {
        val unrecoverable = listOf(1.hours, Duration.ZERO, Duration.ZERO)

        assertNull(TrackDurations.recovered(unrecoverable, 2.hours, anyExcluded = false))
        assert(MediaItems.isSingleFileFallback(unrecoverable))
    }
}
