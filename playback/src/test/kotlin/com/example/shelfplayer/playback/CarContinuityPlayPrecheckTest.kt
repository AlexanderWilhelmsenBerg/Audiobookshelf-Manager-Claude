package com.example.shelfplayer.playback

import org.junit.Test
import kotlin.test.assertEquals

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
}
