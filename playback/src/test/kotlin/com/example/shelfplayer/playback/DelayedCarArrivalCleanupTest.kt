package com.example.shelfplayer.playback

import androidx.media3.common.Player
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** PLAY-002 / #277: characterization of the cleanup/recovery seam, not no-pause acceptance. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DelayedCarArrivalCleanupTest {
    @Test
    fun `known defect - pre-arrival NotConnected cleanup erases delayed recovery`() = runTest {
        DelayedCarArrivalFixture(this).use { fixture ->
            fixture.projection(AndroidAutoProjectionMonitor.State.NotConnected)
            fixture.safetyPause()
            assertEquals(0L, fixture.selectionSequence, "the safety pause itself is not newer selection intent")

            fixture.removeHeadset()
            assertEquals(1L, fixture.selectionSequence, "automatic cleanup creates a selection without a listener")
            assertEquals(OutputIntentOwner.Destination.Automatic, fixture.router.explicitSelection.value?.destination)
            assertNull(fixture.router.selectedId.value)
            fixture.returnHeadset()
            assertEquals(
                "NoFreshPlaybackAfterIntentReset",
                fixture.interrupt(700.milliseconds, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY),
            )
            assertEquals(
                "NoFreshPlaybackAfterIntentReset",
                fixture.interrupt(5.seconds, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS),
            )

            val arrival = fixture.arrive(10_600.milliseconds)
            assertEquals(CarArrivalResumeGate.Status.Rejected, arrival.status)
            assertEquals(CarArrivalResumeGate.Reason.NoPendingFocusLoss, arrival.reason)
            assertNull(arrival.target)
            assertFalse(fixture.player.playWhenReady)
        }
    }

    @Test
    fun `unknown car state retains the same episode through delayed bind and repeated interruptions`() = runTest {
        DelayedCarArrivalFixture(this).use { fixture ->
            fixture.safetyPause()
            fixture.removeHeadset()
            assertEquals(0L, fixture.selectionSequence)
            assertEquals(fixture.endpointId, fixture.router.selectedId.value)
            fixture.returnHeadset()
            fixture.interrupt(700.milliseconds, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
            fixture.interrupt(5.seconds, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)

            val arrival = fixture.arrive(10_600.milliseconds)
            assertEquals(CarArrivalResumeGate.Status.Ready, arrival.status)
            val target = assertNotNull(arrival.target)
            assertEquals(fixture.endpointId, target.outputId)
            assertEquals(30.seconds, target.deadline, "duplicate callbacks cannot extend the first interruption")
            assertTrue(fixture.assess(target).mayPlay)
            assertFalse(fixture.assess(target).mayPlay, "one episode cannot authorize two Plays")
            assertFalse(fixture.player.playWhenReady, "authorization alone does not issue Play or prove audio")
        }
    }

    @Test
    fun `listener Pause after safety pause still cancels retained recovery`() = runTest {
        DelayedCarArrivalFixture(this).use { fixture ->
            fixture.safetyPause()
            fixture.listenerPause()
            fixture.removeHeadset()
            fixture.returnHeadset()
            fixture.interrupt(700.milliseconds, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
            val arrival = fixture.arrive(10_600.milliseconds)
            assertEquals(CarArrivalResumeGate.Status.Rejected, arrival.status)
            assertNull(arrival.target)
            assertFalse(fixture.player.playWhenReady)
        }
    }

    @Test
    fun `known car controller prevents ordinary headset cleanup even before projection updates`() = runTest {
        DelayedCarArrivalFixture(this).use { fixture ->
            fixture.projection(AndroidAutoProjectionMonitor.State.NotConnected)
            fixture.service.carLifecycle.onControllerConnected()
            fixture.safetyPause()
            fixture.removeHeadset()
            assertEquals(0L, fixture.selectionSequence)
            assertEquals(fixture.endpointId, fixture.router.selectedId.value)
            assertFalse(fixture.player.playWhenReady)
        }
    }

    @Test
    fun `newer explicit Car choice still cancels an older headset episode`() = runTest {
        DelayedCarArrivalFixture(this).use { fixture ->
            fixture.safetyPause()
            fixture.removeHeadset()
            fixture.returnHeadset()
            fixture.router.selectCar()
            assertEquals(OutputIntentOwner.Destination.Car, fixture.router.explicitSelection.value?.destination)
            fixture.interrupt(700.milliseconds, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
            val arrival = fixture.arrive(10_600.milliseconds)
            assertEquals(CarArrivalResumeGate.Status.Rejected, arrival.status)
            assertNull(arrival.target)
            assertFalse(fixture.player.playWhenReady)
        }
    }

    @Test
    fun `book replacement cannot inherit the interrupted headset recovery`() = runTest {
        DelayedCarArrivalFixture(this).use { fixture ->
            fixture.safetyPause()
            fixture.removeHeadset()
            fixture.returnHeadset()
            fixture.service.routeOwnership.onBookChanged(hasBook = true)
            fixture.interrupt(700.milliseconds, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
            val arrival = fixture.arrive(10_600.milliseconds)
            assertEquals(CarArrivalResumeGate.Status.Rejected, arrival.status)
            assertNull(arrival.target)
            assertFalse(fixture.player.playWhenReady)
        }
    }
}
