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
        val source = File("src/main/kotlin/com/example/shelfplayer/playback/CarContinuityResumeEffects.kt").readText()
        val resume = source
            .substringAfter("private fun resumeAfterCarContinuity")
            .substringBefore("private suspend fun recoverCarContinuityHeadset")

        val precheck = resume.indexOf("CarContinuityPlayPrecheck.decide")
        val verdict = resume.indexOf("recovery.assess")
        val rejected = resume.indexOf("if (!result.accepted)")
        val acceptedPlay = resume.indexOf("resumePlayer(current, { stillAuthorized(current) })")
        val reservedBeforeRawPlay = resume.indexOf("recovery.notePlayIssued(target)")
        assertTrue(precheck in 0 until verdict, "the precheck precedes coordinator claim consumption")
        assertTrue(rejected > verdict, "a rejected verdict must return before Play")
        assertTrue(acceptedPlay > rejected, "the accepted path must request guarded service Play")
        assertTrue(reservedBeforeRawPlay > acceptedPlay, "awaiting is reserved only on the accepted command path")
        val service = File("src/main/kotlin/com/example/shelfplayer/playback/PlaybackService.kt").readText()
        val serviceEffect = service.substringAfter("private val carResumeEffects by lazy")
            .substringBefore("private fun carSnapshotFields")
        assertTrue(
            "playbackCommandEffects.play(current, stillAuthorized, beforeRawPlay)" in serviceEffect,
            "continuity Play must reach the shared service command gate",
        )
        assertEquals(1, Regex("""\.play\(current, stillAuthorized, beforeRawPlay\)""").findAll(serviceEffect).count())
        val coordinator = File("src/main/kotlin/com/example/shelfplayer/playback/CarContinuityRecoveryCoordinator.kt")
            .readText()
        assertTrue(coordinator.contains("gate.consumeRecovery("), "the coordinator must consume the gate claim")
        assertTrue(coordinator.contains("CarContinuityPlayPrecheck.Proceed ->"), "precheck must gate consumption")
    }
}
