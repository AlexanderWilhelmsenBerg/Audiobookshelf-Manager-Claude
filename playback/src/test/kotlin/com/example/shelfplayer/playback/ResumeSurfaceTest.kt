package com.example.shelfplayer.playback

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC SYNC-002 — the order a resume issues its commands in, pinned.
 *
 * ### Why this test exists
 *
 * Adopting another device's position was two calls from `PlayerViewModel`, `seekTo(remote)` then
 * `togglePlayPause()`, each launching its own coroutine — and only the second one connected. A resume
 * after the media controller had been released therefore played from the stale position, silently: the
 * seek returned early on a null controller and nothing recorded that it had. Reported from a device with
 * a screenshot showing the Play row at the old position and no seek anywhere in the history.
 *
 * Media3's `MediaController` is `final`, so `PlaybackController` cannot be unit tested and the ordering
 * had nowhere to be asserted. [ResumeSurface] is that seam: behind it the order is a pure function over a
 * recorder, and these tests read the recording.
 */
class ResumeSurfaceTest {

    /**
     * The regression itself: prepare, then seek, then play — in that order and no other.
     *
     * Each of the three is load-bearing. A seek issued before `prepare` on an idle player is discarded by
     * Media3, so the prepare cannot come second; a `play` issued before the seek is audible from the
     * position being left behind, which is the whole defect.
     */
    @Test
    fun `a resume that needs preparing prepares, then seeks, then plays`() {
        val surface = RecordingSurface(needsPreparing = true)

        surface.resumeAt(positionMillis = 900_000L)

        assertEquals(listOf("prepare", "seekTo(900000)", "play"), surface.commands)
    }

    /**
     * A loaded, paused player is not re-prepared.
     *
     * `prepare()` on a prepared player is a documented no-op, so this is not about correctness — it is
     * about the ordinary resume issuing exactly the two commands it means and nothing that could reset
     * buffered audio. Product priority 1.
     */
    @Test
    fun `a resume on a prepared player seeks and plays without preparing`() {
        val surface = RecordingSurface(needsPreparing = false)

        surface.resumeAt(positionMillis = 900_000L)

        assertEquals(listOf("seekTo(900000)", "play"), surface.commands)
    }

    /**
     * A remote **rewind** is adopted exactly like a remote fast-forward.
     *
     * The newest server position wins, not the furthest one. Somebody who re-listened to a chapter on the
     * web left the server behind this device, and resuming ahead of where they actually are would skip the
     * part they went back for. `checkServerPosition` decides *whether* a position is newer; once it has
     * said so, this must not second-guess the direction — which a `coerceAtLeast(currentPosition)` here
     * would quietly do.
     */
    @Test
    fun `a server position earlier than the local one is still seeked to`() {
        val surface = RecordingSurface(needsPreparing = false)

        surface.resumeAt(positionMillis = 120_000L)

        assertEquals(listOf("seekTo(120000)", "play"), surface.commands)
    }

    /**
     * `null` — the ordinary Play, with no other device to catch up with — resumes without seeking.
     *
     * The distinction matters: a `seekTo(currentPosition)` would be a visible no-op on most players and a
     * rebuffer on some, and it would put a position change into the session for something that did not
     * move.
     */
    @Test
    fun `a resume with no position to adopt only plays`() {
        val prepared = RecordingSurface(needsPreparing = false)
        val idle = RecordingSurface(needsPreparing = true)

        prepared.resumeAt(positionMillis = null)
        idle.resumeAt(positionMillis = null)

        assertEquals(listOf("play"), prepared.commands)
        assertEquals(listOf("prepare", "play"), idle.commands)
    }

    /**
     * A nonsense position from the server starts the book rather than failing the resume.
     *
     * Product priority 1 decides it: the listener pressed Play. Clamping is the behaviour that keeps that
     * promise, and a negative seek is rejected by Media3 anyway.
     */
    @Test
    fun `a negative position is clamped to the start`() {
        val surface = RecordingSurface(needsPreparing = false)

        surface.resumeAt(positionMillis = -5_000L)

        assertEquals(listOf("seekTo(0)", "play"), surface.commands)
    }

    /** Zero is a real position — the start of the book — and is seeked to, not skipped as falsy. */
    @Test
    fun `a position of zero is seeked to`() {
        val surface = RecordingSurface(needsPreparing = false)

        surface.resumeAt(positionMillis = 0L)

        assertEquals(listOf("seekTo(0)", "play"), surface.commands)
    }

    /**
     * The acceptance case the defect was reported as, end to end through this seam.
     *
     * BookWave paused at 10:00 and synced; the web player listened on to 15:00; the listener came back
     * after more than the pause gate and pressed Play. `checkServerPosition` answers
     * `Ahead(15.minutes)` — that half is covered by `DefaultPlaybackRepositoryTest` — and this is what
     * must then reach the player: a seek to 15:00 **before** the play, so the first sound is from 15:00
     * and not from 10:00.
     */
    @Test
    fun `the reported case - paused at ten minutes, server at fifteen - starts at fifteen`() {
        val surface = RecordingSurface(needsPreparing = true)

        surface.resumeAt(positionMillis = FIFTEEN_MINUTES)

        assertEquals(listOf("prepare", "seekTo($FIFTEEN_MINUTES)", "play"), surface.commands)
        assertTrue(
            surface.commands.indexOf("seekTo($FIFTEEN_MINUTES)") < surface.commands.indexOf("play"),
            "the seek must precede the play, or the listener hears the position being left behind",
        )
    }

    // ------------------------------------------- did the seek actually happen

    /*
     * `MediaController` returns `void` from `seekTo` and says nothing about whether the session applied
     * it. A device run found one that was not applied: the history pane carried a complete "moved on
     * another device, 9:59 → 15:58" row, `onPositionDiscontinuity` never fired, and the next sync
     * reported 10:11 — audio that had never left 9:59. These pin the reading that tells the two apart.
     */

    /** Playing on from the adopted position, a second later. This is the resume having worked. */
    @Test
    fun `a position near the target counts as adopted`() {
        assertTrue(didAdopt(landed = 15.minutes + 59.seconds, from = 10.minutes, target = 15.minutes + 58.seconds))
    }

    /** The reported failure: still playing on from where it started, with the target ignored. */
    @Test
    fun `a position still near the start counts as not adopted`() {
        assertFalse(didAdopt(landed = 10.minutes + 11.seconds, from = 10.minutes, target = 15.minutes + 58.seconds))
    }

    /**
     * A **remote rewind** reads the same way round, which a magnitude comparison would get wrong.
     *
     * Somebody re-listening to a chapter on the web leaves the server *behind* this device. Landing near
     * the smaller number is the resume having worked, not having failed.
     */
    @Test
    fun `a rewind that landed counts as adopted`() {
        assertTrue(didAdopt(landed = 2.minutes + 1.seconds, from = 15.minutes, target = 2.minutes))
        assertFalse(didAdopt(landed = 15.minutes + 1.seconds, from = 15.minutes, target = 2.minutes))
    }

    /**
     * Exactly between the two is **not** adopted, and the tie belongs on that side deliberately.
     *
     * A resume that cannot be shown to have moved has not been shown to have moved. The recovery costs a
     * rebuffer; leaving the listener in the wrong place costs them the thing the feature exists for.
     */
    @Test
    fun `a position exactly between the two is not adopted`() {
        assertFalse(didAdopt(landed = 10.minutes, from = 5.minutes, target = 15.minutes))
    }

    /**
     * Records what a resume asked the player to do, in order.
     *
     * A list of strings rather than a sealed hierarchy: the assertion these tests make is about sequence,
     * and the shortest thing that shows a wrong sequence in a failure message is the sequence itself.
     */
    private class RecordingSurface(override val needsPreparing: Boolean) : ResumeSurface {
        val commands = mutableListOf<String>()

        override fun prepare() {
            commands += "prepare"
        }

        override fun seekTo(positionMillis: Long) {
            commands += "seekTo($positionMillis)"
        }

        override fun play() {
            commands += "play"
        }
    }

    private companion object {
        const val FIFTEEN_MINUTES = 900_000L
    }
}
