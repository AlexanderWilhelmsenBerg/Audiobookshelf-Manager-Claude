package com.example.shelfplayer.feature.book

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #202 — a Download tap is acknowledged before its request runs, and exactly one request runs per tap.
 *
 * The request is a stand-in for `DownloadBookUseCase`: these cases are about when the button may say
 * *starting*, not about whether the download is allowed, which stays entirely the use case's decision.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadStartTrackerTest {

    private val tracker = DownloadStartTracker()

    @Test
    fun `the tap is acknowledged before the request has even started`() = runTest {
        var requested = 0

        val started = tracker.start(
            scope = this,
            request = {
                requested += 1
                AppResult.Success(Unit)
            },
            onResult = {},
            awaitShown = {},
        )

        assertTrue(started)
        assertTrue(tracker.isStarting.value, "the button must change on the frame of the tap")
        assertEquals(0, requested, "nothing has run yet: the acknowledgement does not wait for the checks")

        advanceUntilIdle()

        assertEquals(1, requested)
        assertFalse(tracker.isStarting.value)
    }

    @Test
    fun `a second tap while the first request runs does not request again`() = runTest {
        val release = CompletableDeferred<AppResult<Unit>>()
        var requested = 0
        val request: suspend () -> AppResult<Unit> = {
            requested += 1
            release.await()
        }

        assertTrue(tracker.start(this, request, onResult = {}, awaitShown = {}))
        advanceUntilIdle()
        assertFalse(tracker.start(this, request, onResult = {}, awaitShown = {}), "the second tap is absorbed")

        release.complete(AppResult.Success(Unit))
        advanceUntilIdle()

        assertEquals(1, requested)
    }

    @Test
    fun `a refusal is reported and the button reverts`() = runTest {
        val refusal = AppResult.Failure(AppError.Storage(summary = "There is not enough space for this book."))
        val reported = mutableListOf<AppResult<Unit>>()
        var awaited = 0

        tracker.start(
            scope = this,
            request = { refusal },
            onResult = { result -> reported += result },
            awaitShown = { awaited += 1 },
        )
        advanceUntilIdle()

        assertEquals(listOf<AppResult<Unit>>(refusal), reported)
        assertEquals(0, awaited, "a refused download is never waited for")
        assertFalse(tracker.isStarting.value, "the button must not keep claiming a download that was refused")
    }

    @Test
    fun `a success keeps the acknowledgement until the screen shows the download`() = runTest {
        val shown = CompletableDeferred<Unit>()
        val reported = mutableListOf<AppResult<Unit>>()

        tracker.start(
            scope = this,
            request = { AppResult.Success(Unit) },
            onResult = { result -> reported += result },
            awaitShown = { shown.await() },
        )
        advanceUntilIdle()

        assertEquals(1, reported.size)
        assertTrue(tracker.isStarting.value, "no flicker back to Download before the manifest is on screen")

        shown.complete(Unit)
        advanceUntilIdle()

        assertFalse(tracker.isStarting.value)
        assertTrue(tracker.start(this, { AppResult.Success(Unit) }, onResult = {}, awaitShown = {}))
        advanceUntilIdle()
    }

    @Test
    fun `a request that throws still lets go of the button`() = runTest {
        val failures = mutableListOf<Throwable>()
        // Not a child of the test: the throw must reach a scope's handler, not fail the test itself.
        val handler = CoroutineExceptionHandler { _, failure -> failures += failure }
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + handler)

        tracker.start(
            scope = scope,
            request = { throw IllegalStateException("the request threw") },
            onResult = {},
            awaitShown = {},
        )
        advanceUntilIdle()

        assertEquals(1, failures.size, "the failure still reaches the scope rather than being swallowed")
        assertFalse(tracker.isStarting.value)
    }
}
