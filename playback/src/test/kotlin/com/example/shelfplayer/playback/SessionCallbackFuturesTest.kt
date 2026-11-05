package com.example.shelfplayer.playback

import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCallbackFuturesTest {
    private val events = mutableListOf<LogEvent>()
    private val logger = object : Logger {
        override fun log(event: LogEvent) {
            events += event
        }
    }

    @Test
    fun `already completed playback delivers one compound passive action then restores context`() = runTest {
        val bridge = SessionCallbackFutures(this, UnconfinedTestDispatcher(testScheduler), logger)
        var current = true
        val future = bridge.submitPlayback({ current }, { it == "queue" }) { "queue" }
        assertTrue(future.isDone)
        future.addListener({
            assertTrue(bridge.isPlaybackResumptionDelivery())
            current = false // Installing media advances the service's existing command generation.
            assertTrue(bridge.playerEffectsAllowed(), "its own install must not revoke the following Play")
        }, java.util.concurrent.Executor(Runnable::run))
        assertTrue(bridge.playerEffectsAllowed())
        assertFalse(bridge.isPlaybackResumptionDelivery(), "later controller Play is explicit")
    }

    @Test
    fun `queued playback listener checks ownership at execution rather than registration`() = runTest {
        val bridge = SessionCallbackFutures(this, UnconfinedTestDispatcher(testScheduler), logger)
        val queued = mutableListOf<Runnable>()
        var current = true
        val future = bridge.submitPlayback({ current }, { true }) { "queue" }
        future.addListener({
            assertFalse(bridge.playerEffectsAllowed())
            assertFalse(bridge.isPlaybackResumptionDelivery())
        }, java.util.concurrent.Executor { queued += it })
        current = false
        queued.single().run()
        assertTrue(bridge.playerEffectsAllowed(), "the guard must not swallow a later genuine Play")
    }

    @Test
    fun `cancelled cold future rejects fallback effects even with a queued listener`() = runTest {
        val bridge = SessionCallbackFutures(this, UnconfinedTestDispatcher(testScheduler), logger)
        val queued = mutableListOf<Runnable>()
        var completeDependency: () -> Unit = { error("dependency was not called") }
        val future = bridge.submitPlayback({ true }, { true }) {
            // Model a callback API that ignores cancellation and returns a value late.
            suspendCoroutine<String> { continuation ->
                completeDependency = { continuation.resume("late queue") }
            }
        }
        future.addListener({
            assertFalse(bridge.playerEffectsAllowed())
        }, java.util.concurrent.Executor { queued += it })
        assertTrue(future.cancel(false))
        completeDependency()
        queued.single().run()
        assertTrue(bridge.playerEffectsAllowed())
    }

    @Test
    fun `empty or failed cold future cannot authorize framework fallback Play`() = runTest {
        val bridge = SessionCallbackFutures(this, UnconfinedTestDispatcher(testScheduler), logger)
        val empty = bridge.submitPlayback({ true }, { it.isNotEmpty() }) { emptyList<String>() }
        val failed = bridge.submitPlayback<String>({ true }, { true }) { error("unavailable") }
        listOf(empty, failed).forEach { future ->
            future.addListener({
                assertFalse(bridge.playerEffectsAllowed())
            }, java.util.concurrent.Executor(Runnable::run))
        }
        assertTrue(bridge.playerEffectsAllowed())
    }

    @Test
    fun `stale callback cancellation completes the Media3 future`() = runTest {
        val bridge = SessionCallbackFutures(this, UnconfinedTestDispatcher(testScheduler), logger)
        val result = bridge.submit<String> { throw CancellationException("superseded") }
        assertTrue(result.isDone)
        assertTrue(result.isCancelled)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `controller cancellation cancels suspended work`() = runTest {
        val stopped = CompletableDeferred<Unit>()
        val bridge = SessionCallbackFutures(this, UnconfinedTestDispatcher(testScheduler), logger)
        val result = bridge.submit {
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        assertFalse(result.isDone)
        assertTrue(result.cancel(false))
        stopped.await()
        assertTrue(result.isCancelled)
    }

    @Test
    fun `already cancelled scope cannot strand a controller future`() = runTest {
        val job = SupervisorJob().apply { cancel() }
        val bridge = SessionCallbackFutures(CoroutineScope(job), UnconfinedTestDispatcher(testScheduler), logger)
        val result = bridge.submit { "unreachable" }
        assertTrue(result.isCancelled)
    }

    @Test
    fun `failure diagnostics never carry exception message with private URL`() = runTest {
        val bridge = SessionCallbackFutures(this, UnconfinedTestDispatcher(testScheduler), logger)
        val result = bridge.submit<String> { error("https://private.example/book-title") }
        assertIs<ExecutionException>(runCatching(result::get).exceptionOrNull())
        assertFalse(events.toString().contains("private.example"))
        assertFalse(events.toString().contains("book-title"))
        assertEquals(1, events.size)
    }
}
