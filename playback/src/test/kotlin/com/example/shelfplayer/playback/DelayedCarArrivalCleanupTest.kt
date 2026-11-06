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

/** PLAY-002 / #277: cleanup must not erase a qualified interrupted headset episode. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DelayedCarArrivalCleanupTest {
    @Test
    fun `prediction during interruption cannot reset expiry or revive deliberate Pause`() = runTest {
        DelayedCarArrivalFixture(this).use { fixture ->
            fixture.safetyPause()
            fixture.advanceTo(5.seconds)
            fixture.observeSpeakerPrediction()
            fixture.interrupt(6.seconds, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
            val target = assertNotNull(fixture.arrive(10.seconds).target)
            assertEquals(30.seconds, target.deadline)
            fixture.listenerPause()
            fixture.observeSpeakerPrediction()
            assertFalse(fixture.assess(target).mayPlay)
        }
    }

    @Test
    fun `three arrival departure cycles retain measured identity across speaker prediction gaps`() = runTest {
        DelayedCarArrivalFixture(this).use { fixture ->
            repeat(3) { cycle ->
                val start = (cycle * 20).seconds
                fixture.advanceTo(start)
                fixture.projection(AndroidAutoProjectionMonitor.State.NotConnected)
                fixture.observeSpeakerPrediction()
                fixture.removeHeadset()
                assertEquals(0L, fixture.selectionSequence, "prediction cannot turn cleanup into listener intent")
                fixture.returnHeadset()
                fixture.interrupt(start + 1.seconds, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
                val target = assertNotNull(fixture.arrive(start + 3.seconds).target)
                assertTrue(fixture.assess(target).mayPlay)
                assertFalse(fixture.assess(target).mayPlay)
                fixture.resumeMeasuredPlayback()
                fixture.interrupt(start + 10.seconds, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
                assertEquals(CarArrivalResumeGate.Status.Ready, fixture.depart(start + 11.seconds).status)
                fixture.resumeMeasuredPlayback()
            }
        }
    }

    @Test
    fun `pre-arrival NotConnected cleanup preserves bounded delayed recovery`() = runTest {
        DelayedCarArrivalFixture(this).use { fixture ->
            fixture.projection(AndroidAutoProjectionMonitor.State.NotConnected)
            fixture.safetyPause()
            assertEquals(0L, fixture.selectionSequence, "the safety pause itself is not newer selection intent")

            fixture.removeHeadset()
            assertEquals(0L, fixture.selectionSequence, "inventory cleanup must not invent listener selection intent")
            assertEquals(fixture.endpointId, fixture.router.selectedId.value)
            fixture.returnHeadset()
            fixture.interrupt(700.milliseconds, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
            fixture.interrupt(5.seconds, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)

            val arrival = fixture.arrive(10_600.milliseconds)
            assertEquals(CarArrivalResumeGate.Status.Ready, arrival.status)
            val target = assertNotNull(arrival.target)
            assertEquals(fixture.endpointId, target.outputId)
            assertEquals(30.seconds, target.deadline)
            assertTrue(fixture.assess(target).mayPlay)
            assertFalse(fixture.assess(target).mayPlay)
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
