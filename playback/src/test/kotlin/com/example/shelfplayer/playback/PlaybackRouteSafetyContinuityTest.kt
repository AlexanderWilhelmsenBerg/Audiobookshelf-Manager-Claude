package com.example.shelfplayer.playback

import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackRouteSafetyContinuityTest {
    @Test
    fun `internal route pause retains arrival evidence but later listener pause revokes it`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            val service = fixture.service
            val recovery = service.playbackField<CarContinuityRecoveryCoordinator>("carRecovery")
            val owner = service.routeOwnership
            // The shared command fixture seeds a prior arrival; close it before modelling a new connection.
            recovery.carDeparture(11.seconds, owner.currentGeneration, 0, physicalProjection = false)
            fixture.playbackField<TestAppClock>("clock").advanceBy(11.seconds)
            val inputs = CarContinuityRecoveryCoordinator.Inputs(
                owner.heardRoute,
                fixture.endpointId,
                owner.currentGeneration,
                0,
                carConnected = false,
            )
            fixture.checkRouteSafetyPauseReason(
                afterSafetyPause = {
                    val interrupted = recovery.platformInterruption(12.seconds, inputs, focusLoss = false)
                    assertEquals(CarArrivalResumeGate.Status.Armed, interrupted.status)
                    val arrival = recovery.carArrival(13.seconds, owner.currentGeneration, 0)
                    assertEquals(CarArrivalResumeGate.Status.Ready, arrival.status)
                    assertEquals(fixture.endpointId, arrival.target?.outputId)
                },
                afterListenerPause = {
                    val interrupted = recovery.platformInterruption(14.seconds, inputs, focusLoss = true)
                    assertEquals(CarArrivalResumeGate.Reason.NoFreshPlaybackAfterIntentReset, interrupted.reason)
                },
            )
        }
    }

    @Test
    fun `an old safety pause cannot arm a later unrelated car connection`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            val recovery = fixture.service.playbackField<CarContinuityRecoveryCoordinator>("carRecovery")
            val owner = fixture.service.routeOwnership
            val inputs = CarContinuityRecoveryCoordinator.Inputs(
                owner.heardRoute,
                fixture.endpointId,
                owner.currentGeneration,
                0,
                carConnected = false,
            )
            fixture.checkRouteSafetyPauseReason(afterSafetyPause = {
                val interrupted = recovery.platformInterruption(7.seconds, inputs, focusLoss = true)
                assertEquals(CarArrivalResumeGate.Status.Rejected, interrupted.status)
                assertEquals(CarArrivalResumeGate.Reason.OutsidePairingWindow, interrupted.reason)
            })
        }
    }
}
