package com.example.shelfplayer.playback

import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Proves route-safety rearming through PlaybackService.PlayerEvents and its real effects owner. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceSafetyRearmTest {
    @Test
    fun `service does not rearm issued Play until actual route and active proof arrive`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { fixture ->
            fixture.installServiceContinuityCallbacks()
            val recovery = fixture.service.playbackField<CarContinuityRecoveryCoordinator>("carRecovery")
            val target = fixture.actualArrivalTarget()
            assertTrue(
                recovery.assess(target, CarContinuityPlayPrecheck.Proceed, target.generation, 0, fixture.endpointId)
                    .mayPlay,
            )
            assertTrue(
                fixture.service.playbackCommandEffects.play(fixture.player, { true }) {
                    recovery.notePlayIssued(target)
                },
            )
            assertEquals(1, fixture.rawPlayCalls)

            fixture.connectCarSession()
            fixture.advanceElapsedClock(12.seconds)
            fixture.removeHeadsetFromInventory()
            assertTrue(fixture.pauseForSafetyThroughService())
            runCurrent()

            assertEquals(1, fixture.rawPlayCalls)
            assertNull(fixture.pendingEffectsTarget())
        }
    }

    @Test
    fun `measured first recovery allows service safety rearm while headset inventory is absent`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { fixture ->
            val recovery = setupConfirmedAttempt(fixture)

            fixture.connectCarSession()
            fixture.advanceElapsedClock(12.seconds)
            fixture.removeHeadsetFromInventory()
            assertTrue(fixture.pauseForSafetyThroughService())
            runCurrent()

            val repeated = assertNotNull(fixture.pendingEffectsTarget())
            assertEquals(2, repeated.attemptOrdinal)
            assertEquals(recovery.episodeId, repeated.episodeId)

            fixture.returnMeasuredHeadset()
            runCurrent()
            assertEquals(2, fixture.rawPlayCalls, "the service effects must issue the second claimed Play")
        }
    }

    @Test
    fun `listener Pause cancels service rearm before a missing headset returns`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { fixture ->
            setupConfirmedAttempt(fixture)
            fixture.connectCarSession()
            fixture.advanceElapsedClock(12.seconds)
            fixture.removeHeadsetFromInventory()
            assertTrue(fixture.pauseForSafetyThroughService())
            runCurrent()
            assertEquals(2, assertNotNull(fixture.pendingEffectsTarget()).attemptOrdinal)

            assertNull(fixture.forwarding.handleSetPlayWhenReady(false).get())
            runCurrent()
            fixture.returnMeasuredHeadset()
            runCurrent()

            assertEquals(1, fixture.rawPlayCalls, "the cancelled service rearm must not issue Play")
            assertFalse(fixture.player.playWhenReady)
            assertNull(fixture.pendingEffectsTarget())
        }
    }

    private fun setupConfirmedAttempt(fixture: PlaybackServiceCommandContinuityFixture): CarArrivalResumeGate.Target {
        fixture.installServiceContinuityCallbacks()
        val recovery = fixture.service.playbackField<CarContinuityRecoveryCoordinator>("carRecovery")
        val outcomes = mutableListOf<CarContinuityRecoveryCoordinator.PlayOutcome>()
        recovery.onPlayOutcome = outcomes::add
        val target = fixture.actualArrivalTarget()

        fixture.issueAndConfirmRecoveryPlay(target)

        assertEquals(1, fixture.rawPlayCalls)
        assertTrue(outcomes.any { it is CarContinuityRecoveryCoordinator.PlayOutcome.Issued })
        assertTrue(outcomes.any { it is CarContinuityRecoveryCoordinator.PlayOutcome.AudibleConfirmed })
        return target
    }
}
