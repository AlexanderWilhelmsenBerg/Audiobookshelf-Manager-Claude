package com.example.shelfplayer.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PLAY-002/ROUTE-001: ordinary headset replacement must not inherit a departed headset's guard. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DisconnectedHeadsetSelectionTest {
    @Test
    fun `new selection during the continuity query cannot be overwritten by retirement`() = runTest {
        val fixture = fixture()
        fixture.guard("departed", connected = false)
        fixture.router.confirmedCarAbsent = { true }
        var queries = 0
        fixture.router.retainsInterruptedHeadset = {
            queries++
            if (queries == 2) fixture.router.outputIntentOwner.select(null, OutputIntentOwner.Destination.Car)
            false
        }
        assertFalse(fixture.router.handoffs.retireDisconnectedOutput("departed", preserveContinuity = true))
        assertEquals(2, queries)
        assertEquals(OutputIntentOwner.Destination.Car, fixture.router.explicitSelection.value?.destination)
        assertEquals(emptyList(), fixture.preferredRequests)
    }

    @Test
    fun `disconnect retires unlocked choice and guard before releasing preference without autoplay`() = runTest {
        val fixture = fixture()
        fixture.adoptGuard("departed")
        fixture.router.confirmedCarAbsent = { true }

        refresh(fixture)
        runCurrent()

        assertFalse(fixture.player.playWhenReady)
        assertNull(fixture.router.selectedId.value)
        assertNull(fixture.guardId())
        assertEquals(OutputIntentOwner.Destination.Automatic, fixture.router.explicitSelection.value?.destination)
        assertEquals(listOf(null), fixture.preferredRequests)
        fixture.observeEndpoint(201, "Replacement")
        assertEquals(ManualPlayProtection.NoGuard, fixture.router.protectManualPlayOnGuardedHeadset(fixture.player))
        assertEquals(listOf(null), fixture.preferredRequests, "native preference is cleared before Play can proceed")
        assertFalse(fixture.player.playWhenReady, "preparation is not Play")
    }

    @Test
    fun `manual Play can retire implicit stale guard after absence becomes known`() = runTest {
        val fixture = fixture()
        fixture.guard("departed", connected = false)
        fixture.player.pause()
        assertEquals(ManualPlayProtection.Denied, fixture.router.protectManualPlayOnGuardedHeadset(fixture.player))
        fixture.router.confirmedCarAbsent = { true }
        fixture.observeEndpoint(202, "Replacement")

        assertEquals(ManualPlayProtection.NoGuard, fixture.router.protectManualPlayOnGuardedHeadset(fixture.player))
        runCurrent()
        assertNull(fixture.guardId())
        assertFalse(fixture.player.playWhenReady)
    }

    @Test
    fun `unknown or active car retains exact absent target despite another headset`() = runTest {
        val fixture = fixture()
        fixture.adoptGuard("departed")
        refresh(fixture)
        runCurrent()
        fixture.observeEndpoint(203, "Replacement")

        assertEquals("departed", fixture.router.selectedId.value)
        assertEquals("departed", fixture.guardId())
        assertEquals(ManualPlayProtection.Denied, fixture.router.protectManualPlayOnGuardedHeadset(fixture.player))
        assertFalse(fixture.player.playWhenReady)
        assertEquals(emptyList(), fixture.preferredRequests)
    }

    @Test
    fun `explicit lock survives disconnect even with confirmed car absence`() = runTest {
        val fixture = fixture()
        fixture.guard("departed")
        fixture.router.outputIntentOwner.keepHere("departed", 1, 0)
        fixture.router.confirmedCarAbsent = { true }
        refresh(fixture)
        runCurrent()
        fixture.observeEndpoint(204, "Replacement")

        assertEquals("departed", fixture.router.outputLock.value?.outputId)
        assertEquals("departed", fixture.guardId())
        assertEquals(ManualPlayProtection.Denied, fixture.router.protectManualPlayOnGuardedHeadset(fixture.player))
        assertFalse(fixture.player.playWhenReady)
    }

    @Test
    fun `newer selection during retirement publication cannot be cleared by queued cleanup`() = runTest {
        val fixture = fixture()
        fixture.adoptGuard("departed")
        fixture.router.confirmedCarAbsent = { true }
        var replaced = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.router.explicitSelection.collect {
                if (!replaced && it?.destination == OutputIntentOwner.Destination.Automatic) {
                    replaced = true
                    fixture.adoptGuard("successor")
                }
            }
        }
        refresh(fixture)
        runCurrent()

        assertEquals(true, replaced)
        assertEquals("successor", fixture.router.selectedId.value)
        assertEquals("successor", fixture.guardId())
        assertEquals(emptyList(), fixture.preferredRequests)
        assertFalse(fixture.player.playWhenReady)
    }

    private fun refresh(fixture: Fixture) {
        // Exercise inventory refresh -> disappearance callback, not a second selection policy model.
        AudioOutputRouter::class.java.getDeclaredMethod("refresh").apply { isAccessible = true }.invoke(fixture.router)
    }

    @Test
    fun `car uncertainty during safety pause retains protection`() = runTest {
        var absent = true
        val fixture = fixture(onPause = { absent = false })
        fixture.adoptGuard("departed")
        fixture.router.confirmedCarAbsent = { absent }
        refresh(fixture)
        runCurrent()
        assertEquals("departed", fixture.router.selectedId.value)
        assertEquals("departed", fixture.guardId())
        assertEquals(emptyList(), fixture.preferredRequests)
        assertEquals(ManualPlayProtection.Denied, fixture.router.protectManualPlayOnGuardedHeadset(fixture.player))
    }

    @Test
    fun `reconnection during safety pause retains the exact headset without preference reset`() = runTest {
        lateinit var fixture: Fixture
        fixture = fixture(onPause = { fixture.guard("departed") })
        fixture.adoptGuard("departed")
        fixture.router.confirmedCarAbsent = { true }
        refresh(fixture)
        runCurrent()
        assertEquals("departed", fixture.router.selectedId.value)
        assertEquals("departed", fixture.guardId())
        assertEquals(emptyList(), fixture.preferredRequests)
        assertFalse(fixture.player.playWhenReady)
    }

    @Test
    fun `new lock during safety pause cannot be retired`() = runTest {
        lateinit var fixture: Fixture
        fixture = fixture(onPause = { fixture.router.outputIntentOwner.keepHere("departed", 1, 0) })
        fixture.adoptGuard("departed")
        fixture.router.confirmedCarAbsent = { true }
        refresh(fixture)
        runCurrent()
        assertEquals("departed", fixture.router.outputLock.value?.outputId)
        assertEquals("departed", fixture.guardId())
        assertEquals(emptyList(), fixture.preferredRequests)
    }

    @Test
    fun `service command starts once after stale implicit protection is retired`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.service.audioOutputs.confirmedCarAbsent = { true }
            assertFalse(fixture.player.playWhenReady)
            assertTrue(fixture.commands.play(fixture.player, stillAuthorized = { true }))
            assertEquals(1, fixture.rawPlayCalls)
            assertNull(fixture.service.audioOutputs.selectedId.value)
            assertNull(fixture.service.audioOutputs.positions.guard.endpointId)
        }
    }

    @Test
    fun `rejected native preference retains protection until a later successful manual retry`() = runTest {
        var reject = true
        val fixture = fixture(onPreference = { check(!reject) { "Synthetic preference refusal" } })
        fixture.guard("departed", connected = false)
        fixture.router.confirmedCarAbsent = { true }
        assertEquals(ManualPlayProtection.Denied, fixture.router.protectManualPlayOnGuardedHeadset(fixture.player))
        assertEquals("departed", fixture.guardId())
        assertEquals("departed", fixture.router.selectedId.value)
        assertFalse(fixture.player.playWhenReady)

        reject = false
        assertEquals(ManualPlayProtection.NoGuard, fixture.router.protectManualPlayOnGuardedHeadset(fixture.player))
        assertNull(fixture.guardId())
        assertNull(fixture.router.selectedId.value)
        assertFalse(fixture.player.playWhenReady)
    }

    @Test
    fun `disconnect retirement cannot bypass an active route verification`() = runTest {
        val fixture = fixture()
        fixture.guard("departed")
        assertTrue(fixture.router.beginMutedHeadsetProbe("departed"))
        val probe = fixture.guardOwner().currentProbe
        fixture.guard("departed", connected = false)
        fixture.router.confirmedCarAbsent = { true }

        assertFalse(fixture.router.handoffs.retireDisconnectedOutput("departed"))
        assertEquals(probe, fixture.guardOwner().currentProbe)
        assertEquals("departed", fixture.guardId())
        assertEquals(emptyList(), fixture.preferredRequests)
    }
}
