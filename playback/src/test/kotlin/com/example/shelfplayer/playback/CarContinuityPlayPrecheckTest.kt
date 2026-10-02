package com.example.shelfplayer.playback

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #128 — the pre-gate decision of the car-arrival continuity resume, plus the proof that the service
 * applies the Play outcome. [CarArrivalResumeGateTest] owns the gate's own eligibility verdicts.
 */
class CarContinuityPlayPrecheckTest {

    @Test
    fun `no player cancels`() {
        assertEquals(
            CarContinuityPlayPrecheck.NoPlayer,
            CarContinuityPlayPrecheck.decide(hasPlayer = false, mediaItemCount = 1, playWhenReady = false),
        )
    }

    @Test
    fun `an empty queue cancels everything`() {
        assertEquals(
            CarContinuityPlayPrecheck.EmptyQueue,
            CarContinuityPlayPrecheck.decide(hasPlayer = true, mediaItemCount = 0, playWhenReady = false),
        )
    }

    @Test
    fun `already active playback intent is never doubled`() {
        assertEquals(
            CarContinuityPlayPrecheck.AlreadyPlaying,
            CarContinuityPlayPrecheck.decide(hasPlayer = true, mediaItemCount = 1, playWhenReady = true),
        )
    }

    @Test
    fun `a loaded paused player proceeds to the gate`() {
        assertEquals(
            CarContinuityPlayPrecheck.Proceed,
            CarContinuityPlayPrecheck.decide(hasPlayer = true, mediaItemCount = 2, playWhenReady = false),
        )
    }

    @Test
    fun `an empty queue wins over active intent`() {
        assertEquals(
            CarContinuityPlayPrecheck.EmptyQueue,
            CarContinuityPlayPrecheck.decide(hasPlayer = true, mediaItemCount = 0, playWhenReady = true),
        )
    }

    @Test
    fun `the service issues Play only after an accepted verdict and a Proceed precheck`() {
        val source = File("src/main/kotlin/com/example/shelfplayer/playback/PlaybackService.kt").readText()
        val resume = source
            .substringAfter("private fun resumeAfterCarContinuity")
            .substringBefore("private fun ensureAutoTrace")

        val precheck = resume.indexOf("CarContinuityPlayPrecheck.decide")
        val verdict = resume.indexOf("carContinuity.consumeRecovery")
        val rejected = resume.indexOf("if (!result.accepted)")
        val play = resume.indexOf("current.play()")
        assertTrue(precheck in 0 until verdict, "the precheck precedes the gate")
        assertTrue(rejected > verdict, "a rejected verdict must return before Play")
        assertTrue(play > rejected, "the accepted path must issue Play")
        assertEquals(1, Regex("""current\.play\(\)""").findAll(resume).count(), "exactly one Play")
    }
}
