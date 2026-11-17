package com.example.shelfplayer.playback

import android.media.AudioManager
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

/** APK 2277: inferred Play is confirmed, then another route loss precedes Auto's first bind. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceInferredSafetyRearmTest {
    @Test
    fun `confirmed inferred playback can recover a second safety loss before or after Auto binds`() = runTest {
        for (bindFirst in listOf(false, true)) {
            PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
                val first = startAttempt(f, confirm = true)
                if (bindFirst) f.connectCarSession()
                f.advanceElapsedClock(394.milliseconds)

                val second = interrupt(f)
                assertEquals(2, second.attemptOrdinal)
                assertEquals(first.episodeId, second.episodeId)
                assertEquals(first.deadline, second.deadline)
                assertEquals(first.outputId, second.outputId)
                assertEquals(first.generation, second.generation)
                assertEquals(first.explicitSelectionSequence, second.explicitSelectionSequence)
                assertEquals(1, f.rawPlayCalls, "a missing route cannot be resumed")

                // Duplicate safety notification and the later real bind must keep the same claim.
                f.service.playbackField<CarContinuityRecoveryCoordinator>("carRecovery").routeSafetyPause(
                    f.service.clock.elapsed(),
                    inputs(f),
                )
                f.connectCarSession()
                runCurrent()
                assertEquals(second, f.pendingEffectsTarget())
                f.returnMeasuredHeadset()
                runCurrent()
                assertEquals(2, f.rawPlayCalls)
                assertTrue(f.isRouteMuted(), "old route proof cannot unmute the new attempt")
                f.observeAudibleServiceRoute()
                assertFalse(f.isRouteMuted())
                f.connectCarSession()
                runCurrent()
                assertEquals(2, f.rawPlayCalls, "the bind cannot create another Play")
            }
        }
    }

    @Test
    fun `inferred safety recovery keeps the three Play budget before the bind`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            val first = startAttempt(f, confirm = true)
            for (ordinal in 2..3) {
                val next = interrupt(f)
                assertEquals(ordinal, next.attemptOrdinal)
                assertEquals(first.episodeId, next.episodeId)
                assertEquals(first.deadline, next.deadline)
                f.returnMeasuredHeadset()
                runCurrent()
                assertEquals(ordinal, f.rawPlayCalls)
                f.observeAudibleServiceRoute()
            }
            f.removeHeadsetFromInventory()
            assertTrue(f.pauseForSafetyThroughService())
            runCurrent()
            assertNull(f.pendingEffectsTarget())
            f.returnMeasuredHeadset()
            f.connectCarSession()
            advanceTimeBy(4.seconds)
            runCurrent()
            assertEquals(3, f.rawPlayCalls)
            assertFalse(f.player.playWhenReady)
        }
    }

    @Test
    fun `listener Pause cancels inferred safety recovery while the headset is missing`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            startAttempt(f, confirm = true)
            interrupt(f)

            assertNull(f.forwarding.handleSetPlayWhenReady(false).get())
            runCurrent()
            f.returnMeasuredHeadset()
            f.connectCarSession()
            advanceTimeBy(4.seconds)
            runCurrent()

            assertEquals(1, f.rawPlayCalls)
            assertFalse(f.player.playWhenReady)
            assertNull(f.pendingEffectsTarget())
        }
    }

    @Test
    fun `waiting inferred safety recovery expires at the original deadline`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            val first = startAttempt(f, confirm = true)
            f.advanceElapsedClock(29.seconds)
            val second = interrupt(f)
            assertEquals(first.deadline, second.deadline)

            f.advanceElapsedClock(1.seconds)
            advanceTimeBy(1.seconds)
            f.returnMeasuredHeadset()
            f.connectCarSession()
            runCurrent()

            assertEquals(1, f.rawPlayCalls)
            assertFalse(f.player.playWhenReady)
            val recovery = f.service.playbackField<CarContinuityRecoveryCoordinator>("carRecovery")
            assertFalse(recovery.episodeValid(second))
        }
    }

    @Test
    fun `unconfirmed inferred Play cannot rearm from route safety or inventory return`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            startAttempt(f, confirm = false)
            f.removeHeadsetFromInventory()
            assertTrue(f.pauseForSafetyThroughService())
            runCurrent()
            f.returnMeasuredHeadset()
            f.connectCarSession()
            advanceTimeBy(4.seconds)
            runCurrent()
            assertEquals(1, f.rawPlayCalls)
            assertFalse(f.player.playWhenReady)
            assertNull(f.pendingEffectsTarget())
        }
    }

    private fun TestScope.startAttempt(
        f: PlaybackServiceCommandContinuityFixture,
        confirm: Boolean,
    ): CarArrivalResumeGate.Target {
        f.installServiceContinuityCallbacks()
        f.startHealthyHeadsetWithoutCar()
        f.service.audioMode = { AudioManager.MODE_NORMAL }
        f.service.carBluetoothWindow.onCarHandsFreeConnected("car")
        val outcomes = mutableListOf<CarContinuityRecoveryCoordinator.PlayOutcome>()
        f.service.playbackField<CarContinuityRecoveryCoordinator>("carRecovery").onPlayOutcome = outcomes::add
        f.interruptUnconfirmedPlayForFocusLoss()
        runCurrent()
        val first = assertNotNull(f.pendingEffectsTarget())
        assertTrue(first.inferredArrival)
        assertEquals(30.seconds, first.deadline)
        advanceTimeBy(CarContinuityResumeEffects.INFERRED_ARRIVAL_SETTLE + 1.milliseconds)
        runCurrent()
        assertEquals(1, f.rawPlayCalls)
        assertTrue(f.isRouteMuted())
        if (confirm) {
            f.observeAudibleServiceRoute()
            assertTrue(outcomes.any { it is CarContinuityRecoveryCoordinator.PlayOutcome.AudibleConfirmed })
        }
        return first
    }

    private fun TestScope.interrupt(f: PlaybackServiceCommandContinuityFixture): CarArrivalResumeGate.Target {
        f.removeHeadsetFromInventory()
        assertTrue(f.pauseForSafetyThroughService())
        runCurrent()
        return assertNotNull(f.pendingEffectsTarget(), "a confirmed inferred episode must survive the second loss")
    }

    private fun inputs(f: PlaybackServiceCommandContinuityFixture) = CarContinuityRecoveryCoordinator.Inputs(
        f.service.routeOwnership.heardRoute,
        f.endpointId,
        f.service.routeOwnership.currentGeneration,
        f.explicitSelectionSequence(),
        carConnected = false,
    )
}
