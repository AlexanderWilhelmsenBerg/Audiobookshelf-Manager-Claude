package com.example.shelfplayer.core.model

import kotlinx.coroutines.CancellationException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppResultTest {

    @Test
    fun `map transforms a success and leaves a failure untouched`() {
        val success: AppResult<Int> = AppResult.Success(2)
        val failure: AppResult<Int> = AppResult.Failure(AppError.Timeout())

        assertEquals(AppResult.Success(4), success.map { it * 2 })
        assertEquals(failure, failure.map { it * 2 })
    }

    @Test
    fun `flatMap short-circuits on the first failure`() {
        val error = AppError.Validation(summary = "nope")
        val result = AppResult.Success(1)
            .flatMap<Int, Int> { AppResult.Failure(error) }
            .flatMap<Int, Int> { error("the second step must not run") }

        assertEquals(AppResult.Failure(error), result)
    }

    @Test
    fun `getOrElse yields the fallback for a failure`() {
        val failure: AppResult<String> = AppResult.Failure(AppError.Network())
        assertEquals("fallback", failure.getOrElse { "fallback" })
        assertEquals("value", AppResult.Success("value").getOrElse { "fallback" })
    }

    @Test
    fun `onSuccess and onFailure fire exactly once for the matching case`() {
        var successes = 0
        var failures = 0

        AppResult.Success(Unit).onSuccess { successes++ }.onFailure { failures++ }
        AppResult.Failure(AppError.Network()).onSuccess { successes++ }.onFailure { failures++ }

        assertEquals(1, successes)
        assertEquals(1, failures)
    }

    @Test
    fun `resultOf converts a thrown exception into a typed failure`() {
        val result = resultOf(onError = { AppError.Storage(summary = "disk") }) {
            error("boom")
        }

        assertEquals(AppResult.Failure(AppError.Storage(summary = "disk")), result)
    }

    /**
     * PRODUCT_SPEC 14.2 / 22 — cancellation must propagate.
     *
     * Swallowing it here would break structured concurrency everywhere: a cancelled screen would
     * keep its repository work running and report a "failure" nobody asked about.
     */
    @Test
    fun `resultOf rethrows cancellation instead of mapping it`() {
        assertFailsWith<CancellationException> {
            resultOf<Unit> { throw CancellationException("scope closed") }
        }
    }

    @Test
    fun `errorOrNull and getOrNull expose the matching side only`() {
        val error = AppError.Conflict(summary = "diverged")
        assertNull(AppResult.Success(1).errorOrNull())
        assertEquals(error, AppResult.Failure(error).errorOrNull())
        assertEquals(1, AppResult.Success(1).getOrNull())
        assertNull(AppResult.Failure(error).getOrNull())
    }

    /** PRODUCT_SPEC 14.3 — the retry policy is encoded on the error, not re-derived per call site. */
    @Test
    fun `retryability follows the documented retry policy`() {
        assertTrue(AppError.Network().isRetryable)
        assertTrue(AppError.Timeout().isRetryable)
        assertTrue(AppError.Server(statusCode = 503).isRetryable)

        assertEquals(false, AppError.Authentication().isRetryable)
        assertEquals(false, AppError.Authorization(summary = "denied").isRetryable)
        assertEquals(false, AppError.Server(statusCode = 404).isRetryable)
        assertEquals(false, AppError.ApiCompatibility(summary = "unsupported").isRetryable)
    }
}
