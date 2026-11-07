package com.example.shelfplayer.playback

import androidx.media3.common.util.UnstableApi
import com.example.shelfplayer.core.model.playback.OutputDestination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackGovernorIngressTest {
    @Test
    fun `retiring service cannot detach successor output dispatch and detached choice stays silent`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { f ->
            val port = f.service.audioOutputs.commands
            var retiredCalls = 0
            var successorCalls = 0
            val retired: (() -> Boolean) -> Boolean = { effect ->
                retiredCalls++
                effect()
            }
            val successor: (() -> Boolean) -> Boolean = { effect ->
                successorCalls++
                effect()
            }
            port.attach(retired)
            port.attach(successor)
            port.detach(retired)
            assertTrue(port.select(null, OutputDestination.Car))
            assertEquals(0, retiredCalls)
            assertEquals(1, successorCalls)

            port.detach(successor)
            assertTrue(port.select(null, OutputDestination.Automatic))
            assertEquals(1, successorCalls)
            assertEquals(0, f.rawPlayCalls)
            assertEquals(0, f.rawPrepareCalls)
        }
    }

    @Test
    fun `phone output command immediately revokes suspended continuity without playing`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { f ->
            f.beginRecovery()
            runCurrent()
            assertTrue(f.isRecoveryCurrent())

            f.service.audioOutputs.commands.select(null, OutputDestination.Car)

            assertFalse(f.isRecoveryCurrent())
            f.returnHeadset()
            runCurrent()
            assertEquals(0, f.rawPlayCalls)
            assertEquals(OutputIntentOwner.Destination.Car, f.explicitDestination())
        }
    }

    @Test
    fun `phone output command supersedes queued controller Play through the actual service`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { f ->
            f.armFreshStartForCurrentItem()
            f.configureNoManualGuard()
            val request = f.forwarding.handleSetPlayWhenReady(true)

            f.service.audioOutputs.commands.select(null, OutputDestination.Car)
            runCurrent()

            assertEquals(false, request.get())
            assertEquals(0, f.rawPlayCalls)
        }
    }

    @Test
    fun `closed governor refuses old commands and cancels recovery before a late endpoint return`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { f ->
            f.beginRecovery()
            runCurrent()
            f.service.governor.close()
            assertFalse(f.service.governor.invalidate(ResumeInvalidation.Seek))
            assertFalse(f.service.governor.transport(true, f.player))
            assertFalse(f.commands.play(f.player, stillAuthorized = { true }))
            assertFalse(f.commands.prepare(f.player, stillAuthorized = { true }))
            assertFalse(f.service.audioOutputs.commands.select(null, OutputDestination.Car))
            f.returnHeadset()
            runCurrent()
            assertEquals(0, f.rawPlayCalls)
            assertFalse(f.isRecoveryCurrent())
        }
    }
}
