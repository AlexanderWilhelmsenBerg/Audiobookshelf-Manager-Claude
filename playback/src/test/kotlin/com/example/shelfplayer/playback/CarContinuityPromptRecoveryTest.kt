package com.example.shelfplayer.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class CarContinuityPromptRecoveryTest {
    @Test
    fun `available exact paused headset begins one silent probe without a settling sleep`() = runTest {
        val fixture = CarContinuityResumeTransactionTest.Fixture(this)
        fixture.available.value = listOf(fixture.headset.copy(isActive = false))
        fixture.effects.onRecoveryReady(fixture.target)
        runCurrent()

        assertEquals(1, fixture.current.plays)
        assertEquals(1, fixture.timerResumes)
        assertEquals(0f, fixture.current.volume)
        assertEquals(0, testScheduler.currentTime)
        fixture.effects.onRecoveryReady(fixture.target)
        runCurrent()
        assertEquals(1, fixture.current.plays)

        fixture.current.playing = true
        fixture.actualRoutes = setOf(fixture.headset.id)
        fixture.router.onActualTrackRouteChanged()
        runCurrent()
        assertEquals(1f, fixture.current.volume)
        assertTrue(fixture.current.operations.none { it == "pause" })
    }

    @Test
    fun `return of exact endpoint wakes recovery promptly without polling delay`() = runTest {
        val fixture = CarContinuityResumeTransactionTest.Fixture(this, available = false)
        fixture.effects.onRecoveryReady(fixture.target)
        runCurrent()
        advanceTimeBy(100)
        assertEquals(0, fixture.current.plays)
        fixture.returnHeadset()
        fixture.available.value = listOf(fixture.headset.copy(isActive = false))
        runCurrent()
        assertEquals(1, fixture.current.plays)
        assertEquals(100, testScheduler.currentTime)
        assertEquals(0f, fixture.current.volume)
    }
}
