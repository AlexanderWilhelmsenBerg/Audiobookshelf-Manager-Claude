package com.example.shelfplayer.playback

import com.example.shelfplayer.core.common.time.AppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class CarConnectionRefreshWindowTest {
    @Test
    fun `duplicate interruptions cannot extend or restart the bounded reads`() = runTest {
        val reads = mutableListOf<Long>()
        val window = CarConnectionRefreshWindow(clock()) { reads += testScheduler.currentTime }
        window.start(backgroundScope)
        runCurrent()
        advanceTimeBy(1000)
        window.start(backgroundScope)
        advanceTimeBy(6001)
        runCurrent()
        window.start(backgroundScope)
        runCurrent()
        assertEquals(listOf(0L, 250L, 750L, 1500L, 2500L, 4000L, 6000L), reads)
    }

    @Test
    fun `cancellation stops reads and a later independent interruption can start again`() = runTest {
        val reads = mutableListOf<Long>()
        val window = CarConnectionRefreshWindow(clock()) { reads += testScheduler.currentTime }
        window.start(backgroundScope)
        runCurrent()
        window.cancel()
        advanceTimeBy(7000)
        runCurrent()
        assertEquals(listOf(0L), reads)
        window.start(backgroundScope)
        runCurrent()
        assertEquals(listOf(0L, 7000L), reads)
        window.cancel()
    }

    @Test
    fun `service scope cancellation prevents all scheduled reads`() = runTest {
        val reads = mutableListOf<Int>()
        val window = CarConnectionRefreshWindow(clock()) { reads += it }
        val owner = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.Job())
        window.start(owner)
        owner.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        advanceTimeBy(7000)
        runCurrent()
        assertEquals(emptyList(), reads)
    }

    private fun TestScope.clock(): AppClock = object : AppClock {
        override fun now(): Instant = Instant.EPOCH
        override fun elapsed() = testScheduler.currentTime.milliseconds
    }
}
