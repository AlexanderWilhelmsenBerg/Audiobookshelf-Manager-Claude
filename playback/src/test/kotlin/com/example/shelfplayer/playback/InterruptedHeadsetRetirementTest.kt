package com.example.shelfplayer.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A continuity episode delays inventory cleanup, never deliberate Play or an expired owner. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InterruptedHeadsetRetirementTest {
    @Test
    fun `retirement rechecks the episode created by its own synchronous safety pause`() = runTest {
        DelayedCarArrivalFixture(this).use { fixture ->
            fixture.projection(AndroidAutoProjectionMonitor.State.NotConnected)
            fixture.removeHeadset()
            assertFalse(fixture.player.playWhenReady)
            assertEquals(0L, fixture.selectionSequence)
            assertEquals(fixture.endpointId, fixture.router.selectedId.value)
        }
    }

    @Test
    fun `repeated inventory callbacks cannot extend the thirty second cleanup hold`() = runTest {
        DelayedCarArrivalFixture(this).use { fixture ->
            fixture.projection(AndroidAutoProjectionMonitor.State.NotConnected)
            fixture.safetyPause()
            fixture.removeHeadset()
            fixture.advanceTo(29_999.milliseconds)
            fixture.removeHeadset()
            assertEquals(0L, fixture.selectionSequence)
            fixture.advanceTo(30.seconds)
            fixture.removeHeadset()
            assertEquals(1L, fixture.selectionSequence)
            assertNull(fixture.router.selectedId.value)
            assertFalse(fixture.player.playWhenReady)
        }
    }

    @Test
    fun `manual Play preparation releases a disconnected headset before episode expiry outside Auto`() = runTest {
        DelayedCarArrivalFixture(this).use { fixture ->
            fixture.projection(AndroidAutoProjectionMonitor.State.NotConnected)
            fixture.safetyPause()
            fixture.removeHeadset()
            assertEquals(0L, fixture.selectionSequence)
            assertEquals(
                ManualPlayProtection.NoGuard,
                fixture.router.protectManualPlayOnGuardedHeadset(fixture.player),
            )
            assertEquals(OutputIntentOwner.Destination.Automatic, fixture.router.explicitSelection.value?.destination)
            assertNull(fixture.router.selectedId.value)
            assertFalse(fixture.player.playWhenReady, "preparation does not issue Play")
        }
    }

    @Test
    fun `revoked trusted identity cannot retain an interrupted headset`() = runTest {
        DelayedCarArrivalFixture(this).use { fixture ->
            fixture.projection(AndroidAutoProjectionMonitor.State.NotConnected)
            fixture.safetyPause()
            fixture.removeHeadset()
            assertEquals(0L, fixture.selectionSequence)
            fixture.identityTrusted = false
            fixture.removeHeadset()
            assertNull(fixture.router.selectedId.value)
            assertFalse(fixture.player.playWhenReady)
        }
    }
}
