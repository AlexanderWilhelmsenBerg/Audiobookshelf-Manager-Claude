package com.example.shelfplayer.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarProjectionEarlyReadTest {
    @Test
    fun `qualified focus loss discovers an early provider arrival without waiting for broadcast`() = runTest {
        fixture().use { car ->
            runCurrent()
            car.seedHeadsetViaService()
            car.providerStateWithoutBroadcast(2)
            car.reportFocusLossFromPlayerListener()
            runCurrent()

            assertEquals(1, car.interruptionReads())
            assertEquals(1, car.arrivalDecisions().size)
            assertTrue(car.ownsLifecycle())
            advanceTimeBy(7000)
            runCurrent()
            assertEquals(1, car.interruptionReads(), "a positive boundary stops the burst")
            car.read(2)
            runCurrent()
            assertEquals(1, car.arrivalDecisions().size, "the later broadcast cannot make a second arrival")
        }
    }

    @Test
    fun `unknown provider cannot turn an unrelated focus loss into car arrival`() = runTest {
        fixture().use { car ->
            runCurrent()
            car.seedHeadsetViaService()
            car.providerStateWithoutBroadcast(null)
            car.reportFocusLossFromPlayerListener()
            runCurrent()
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(7, car.interruptionReads())
            assertEquals(0, car.arrivalDecisions().size)
        }
    }

    @Test
    fun `unqualified focus loss does not poll and initial connection never manufactures arrival`() = runTest {
        fixture().use { car ->
            runCurrent()
            car.providerStateWithoutBroadcast(2)
            car.reportFocusLossFromPlayerListener()
            runCurrent()
            assertEquals(0, car.interruptionReads())
            assertEquals(0, car.arrivalDecisions().size)
        }
    }

    @Test
    fun `new listener intent cancels early reads before a later provider change`() = runTest {
        fixture().use { car ->
            runCurrent()
            car.seedHeadsetViaService()
            car.reportFocusLossFromPlayerListener()
            runCurrent()
            assertEquals(1, car.interruptionReads())
            car.cancelListenerIntent()
            car.providerStateWithoutBroadcast(2)
            advanceTimeBy(7000)
            runCurrent()
            assertEquals(1, car.interruptionReads())
            assertEquals(0, car.arrivalDecisions().size)
        }
    }

    private fun TestScope.fixture() = CarProjectionLifecycleTest.Fixture(
        backgroundScope,
        StandardTestDispatcher(testScheduler),
        initialState = 0,
        controllerBindings = 0,
    )
}
