package com.example.shelfplayer.playback

import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Proves route-safety rearming through PlaybackService.PlayerEvents and its real effects owner. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceSafetyRearmTest {
    @Test
    fun `focus retry waits and stays muted until exact track and active playback proof`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { fixture ->
            startUnconfirmedAttempt(fixture)
            fixture.interruptUnconfirmedPlayForFocusLoss()
            runCurrent()
            assertEquals(2, assertNotNull(fixture.pendingEffectsTarget()).attemptOrdinal)
            advanceTimeBy(750.milliseconds)
            fixture.reportPlatformInterruption(
                androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            )
            runCurrent()
            advanceTimeBy(749.milliseconds)
            runCurrent()
            assertEquals(1, fixture.rawPlayCalls)
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(2, fixture.rawPlayCalls)
            assertTrue(fixture.isRouteMuted())
            fixture.observeAudibleServiceRoute()
            assertFalse(fixture.isRouteMuted())
        }
    }

    @Test
    fun `third focus attempt waits three seconds and cannot mint a fourth Play`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { fixture ->
            startUnconfirmedAttempt(fixture)
            fixture.interruptUnconfirmedPlayForFocusLoss()
            runCurrent()
            advanceTimeBy(1500.milliseconds)
            runCurrent()
            assertEquals(2, fixture.rawPlayCalls)
            fixture.interruptUnconfirmedPlayForFocusLoss()
            runCurrent()
            assertEquals(3, assertNotNull(fixture.pendingEffectsTarget()).attemptOrdinal)
            advanceTimeBy(2999.milliseconds)
            runCurrent()
            assertEquals(2, fixture.rawPlayCalls)
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(3, fixture.rawPlayCalls)
            fixture.interruptUnconfirmedPlayForFocusLoss()
            advanceTimeBy(4.seconds)
            runCurrent()
            assertEquals(3, fixture.rawPlayCalls)
            assertFalse(fixture.player.playWhenReady)
            assertNull(fixture.pendingEffectsTarget())
        }
    }

    @Test
    fun `Pause or Stop during focus backoff cancels the second Play`() = runTest {
        for (stop in listOf(false, true)) {
            PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { fixture ->
                startUnconfirmedAttempt(fixture)
                fixture.interruptUnconfirmedPlayForFocusLoss()
                runCurrent()
                assertNotNull(fixture.pendingEffectsTarget())
                if (stop) {
                    assertNull(fixture.forwarding.handleStop().get())
                } else {
                    assertNull(fixture.forwarding.handleSetPlayWhenReady(false).get())
                }
                advanceTimeBy(4.seconds)
                runCurrent()
                assertEquals(1, fixture.rawPlayCalls)
                assertFalse(fixture.player.playWhenReady)
                assertNull(fixture.pendingEffectsTarget())
            }
        }
    }

    private fun TestScope.startUnconfirmedAttempt(fixture: PlaybackServiceCommandContinuityFixture) {
        fixture.installServiceContinuityCallbacks()
        val target = fixture.actualArrivalTarget()
        fixture.connectCarSession()
        val effects = fixture.service.playbackCall("getCarResumeEffects") as CarContinuityResumeEffects
        effects.onRecoveryReady(target)
        runCurrent()
        assertEquals(1, fixture.rawPlayCalls)
        assertTrue(fixture.isRouteMuted())
        fixture.advanceElapsedClock(12.seconds)
    }

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
