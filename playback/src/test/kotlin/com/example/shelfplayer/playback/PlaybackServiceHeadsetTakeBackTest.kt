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

/**
 * PD-009 experiment through the real service callbacks: a trusted headset paused for route safety moments
 * after a car link connected asks for one take-back, and the short return claim waits for the inventory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceHeadsetTakeBackTest {
    @Test
    fun `diagnostic snapshot reads the bound player without issuing playback commands`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            f.installServiceContinuityCallbacks()
            f.service.headsetTakeBack = Recorder()
            f.startHealthyHeadsetWithoutCar()
            val plays = f.rawPlayCalls

            val snapshot = assertNotNull(f.service.audioTrackRouteMonitor.playbackSnapshot())

            assertEquals(f.player.playWhenReady, snapshot.playWhenReady)
            assertEquals(f.player.playbackState, snapshot.state)
            assertEquals(f.player.currentPosition, snapshot.positionMs)
            assertEquals(f.player.totalBufferedDuration, snapshot.bufferedMs)
            assertEquals(true, snapshot.automaticTakeBack)
            assertTrue(
                f.service.audioTrackRouteMonitor.headsetTrialEvidence().contains(
                    "captureMode=normal automaticTakeBack=true",
                ),
            )
            assertEquals(plays, f.rawPlayCalls)
        }
    }

    private class Recorder(private val immediate: Boolean = true) : HeadsetTakeBack {
        override val automaticTransferEnabled = true
        var pending: (() -> Boolean)? = null
        var verified = 0
        var displaced = 0
        override fun onHeadsetVerified(endpointId: String, mayRemember: () -> Boolean) {
            verified++
        }

        override fun onHeadsetDisplaced(
            endpointId: String,
            scope: CarBluetoothArrivalWindow.TakeBackScope,
            isCurrent: () -> Boolean,
        ) {
            displaced++
            pending = { isCurrent() && scope.claim() }
            if (immediate) pending?.invoke()
        }

        override fun cancelPending() {
            pending = null
        }

        override fun close() = cancelPending()
    }

    @Test
    fun `safety pause inside the car window asks for one take-back`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            val takeBack = start(f)
            f.service.carBluetoothWindow.onCarHandsFreeConnected("car")

            assertTrue(f.pauseForSafetyThroughService())
            runCurrent()
            assertEquals(1, takeBack.displaced)

            f.startHealthyHeadsetWithoutCar()
            assertTrue(f.pauseForSafetyThroughService())
            assertEquals(1, takeBack.displaced, "one take-back per car link")
        }
    }

    @Test
    fun `safety pause without a car link asks for nothing`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            val takeBack = start(f)

            assertTrue(f.pauseForSafetyThroughService())

            assertEquals(0, takeBack.displaced)
        }
    }

    @Test
    fun `short return claim waits for the inventory while the displaced headset is still listed`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            start(f)

            assertTrue(f.pauseForSafetyThroughService())
            runCurrent()
            assertEquals(0, f.rawPlayCalls, "the headset is listed but no longer the route")

            f.removeHeadsetFromInventory()
            runCurrent()
            assertEquals(0, f.rawPlayCalls, "an absent headset cannot be probed")

            f.returnMeasuredHeadset()
            runCurrent()
            assertEquals(1, f.rawPlayCalls, "the returned headset is probed once")
            assertTrue(f.isRouteMuted())
        }
    }

    @Test
    fun `short return claim still probes when the inventory never changes`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            start(f)

            assertTrue(f.pauseForSafetyThroughService())
            settle()

            assertEquals(1, f.rawPlayCalls)
            assertTrue(f.isRouteMuted())
            assertTrue(f.player.playWhenReady)
        }
    }

    @Test
    fun `listener Pause revokes a deferred system transfer before a route callback`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            val takeBack = start(f, immediate = false)
            f.service.carBluetoothWindow.onCarHandsFreeConnected("car")
            assertTrue(f.pauseForSafetyThroughService())
            val delayed = assertNotNull(takeBack.pending)

            assertNull(f.forwarding.handleSetPlayWhenReady(false).get())
            runCurrent()

            assertNull(takeBack.pending, "governor listener cancellation reaches the adapter")
            // A callback already copied by the platform also checks its captured playback authority.
            assertFalse(delayed())
        }
    }

    @Test
    fun `newer output selection revokes a deferred system transfer`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { f ->
            val takeBack = start(f, immediate = false)
            f.service.carBluetoothWindow.onCarHandsFreeConnected("car")
            assertTrue(f.pauseForSafetyThroughService())
            val delayed = assertNotNull(takeBack.pending)

            f.selectCar()

            assertFalse(delayed())
        }
    }

    private fun start(f: PlaybackServiceCommandContinuityFixture, immediate: Boolean = true): Recorder {
        val takeBack = Recorder(immediate)
        f.service.headsetTakeBack = takeBack
        f.installServiceContinuityCallbacks()
        f.startHealthyHeadsetWithoutCar()
        return takeBack
    }

    private fun TestScope.settle() {
        advanceTimeBy(CarContinuityResumeEffects.HEADSET_RETURN_INVENTORY_SETTLE.inWholeMilliseconds + 1)
        runCurrent()
    }
}
