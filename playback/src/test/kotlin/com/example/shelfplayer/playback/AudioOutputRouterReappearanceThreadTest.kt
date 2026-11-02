package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Regression for APK2250: headset reappearance must reassert on Media3's player thread. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class AudioOutputRouterReappearanceThreadTest {
    @Test
    fun `paused guarded headset reappearance reasserts on player dispatcher without starting playback`() = runTest {
        withWorkerFixture(this) { fixture, worker, failure, _ ->
            prepareReappearance(fixture)
            fixture.player.pause()
            val pauseCountBeforeReappearance = fixture.pauseThreads.size

            invokeReappearance(fixture)
            drainWorker(worker)
            runCurrent()

            assertTrue(!failure.isCompleted, "reappearance raised on the application worker")
            assertTrue(fixture.readThreads.isNotEmpty(), "reappearance should inspect the attached player")
            assertTrue(fixture.readThreads.all { it === Thread.currentThread() })
            assertEquals(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                (fixture.preferredRequests.single() as AudioDeviceInfo).type,
            )
            assertTrue(!fixture.player.isPlaying, "reappearance must leave paused playback paused")
            assertEquals(
                pauseCountBeforeReappearance,
                fixture.pauseThreads.size,
                "reappearance must not pause or restart",
            )
        }
    }

    @Test
    fun `queued reappearance is invalidated by a newer Car selection`() = runTest {
        withWorkerFixture(this) { fixture, worker, failure, executor ->
            prepareReappearance(fixture)
            val release = blockWorker(executor)
            try {
                invokeReappearance(fixture)
                fixture.router.selectCar()
            } finally {
                release()
            }
            drainWorker(worker)
            assertTrue(!failure.isCompleted, "stale reappearance should not fail on the worker")
            runCurrent()
            assertEquals(null, fixture.router.selectedId.value)
            assertEquals(OutputIntentOwner.Destination.Car, fixture.router.explicitSelection.value?.destination)
            assertTrue(fixture.preferredRequests.all { it == null }, "stale headset work must not override Car")
        }
    }

    @Test
    fun `queued reappearance is invalidated by player detach`() = runTest {
        withWorkerFixture(this) { fixture, worker, failure, executor ->
            prepareReappearance(fixture)
            val release = blockWorker(executor)
            try {
                invokeReappearance(fixture)
                fixture.router.detach()
            } finally {
                release()
            }
            drainWorker(worker)
            assertTrue(!failure.isCompleted, "detached reappearance should not fail on the worker")
            runCurrent()
            assertTrue(fixture.preferredRequests.isEmpty(), "detached reappearance must not apply an output preference")
        }
    }

    private suspend fun withWorkerFixture(
        testScope: TestScope,
        block: suspend (
            Fixture,
            kotlinx.coroutines.CoroutineDispatcher,
            CompletableDeferred<Throwable>,
            java.util.concurrent.ExecutorService,
        ) -> Unit,
    ) {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val failure = CompletableDeferred<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error -> failure.complete(error) },
        )
        try {
            val fixture = testScope.fixture(applicationScope = scope)
            block(fixture, dispatcher, failure, executor)
        } finally {
            scope.cancel()
            dispatcher.close()
        }
    }
}

private fun prepareReappearance(fixture: Fixture) {
    val endpoint = fixture.observeEndpoint(2250, "Reappearing headset")
    fixture.present(endpoint)
    fixture.measuredRoutes { setOf(endpoint.id) }
    fixture.router.outputIntentOwner.onBookChanged(1L)
    fixture.guard(endpoint.id)
    fixture.guardOwner().markMissing()
}

private fun invokeReappearance(fixture: Fixture) {
    AudioOutputRouter::class.java.getDeclaredMethod("onGuardedHeadsetReappeared", String::class.java)
        .apply { isAccessible = true }
        .invoke(fixture.router, fixture.router.selectedId.value)
}

private suspend fun drainWorker(dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
    withTimeout(2_000) { withContext(dispatcher) { Unit } }
}

private fun blockWorker(executor: java.util.concurrent.ExecutorService): () -> Unit {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    // Enqueue a gate on the same single-thread executor before callback work.
    executor.submit {
        started.countDown()
        release.await(2, TimeUnit.SECONDS)
    }
    check(started.await(2, TimeUnit.SECONDS))
    return {
        release.countDown()
    }
}
