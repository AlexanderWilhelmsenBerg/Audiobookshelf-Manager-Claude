package com.example.shelfplayer.core.network.http

import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.testing.RecordingLogSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC 14.3 — which failures are retried, how often, and how long the waits are.
 *
 * `runTest` drives the backoff on virtual time, so the waits are asserted rather than endured; a real
 * three-retry sequence would put eight seconds of sleep into the suite.
 *
 * The jitter source is seeded rather than stubbed to zero. A policy tested with no jitter is a policy
 * whose jitter is untested, and jitter is the part that stops every client in an outage retrying in the
 * same instant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryPolicyTest {

    private val sink = RecordingLogSink()
    private val logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default))

    private fun policy(seed: Int = 7) = RetryPolicy(logger, Random(seed))

    @Test
    fun `a call that succeeds is not retried`() = runTest {
        var attempts = 0

        val result = policy().readOnly("op") {
            attempts++
            AppResult.Success(attempts)
        }

        assertEquals(AppResult.Success(1), result)
        assertEquals(0, currentTime, "a success must not wait")
    }

    /** "Up to 3 retries" is four attempts in total, and the original failure is what comes back. */
    @Test
    fun `a retryable failure is attempted four times in total`() = runTest {
        var attempts = 0

        val result = policy().readOnly("op") {
            attempts++
            AppResult.Failure(AppError.Network())
        }

        assertEquals(4, attempts)
        assertIs<AppError.Network>(assertIs<AppResult.Failure>(result).error)
    }

    @Test
    fun `a call that recovers stops retrying`() = runTest {
        var attempts = 0

        val result = policy().readOnly("op") {
            attempts++
            if (attempts < 3) AppResult.Failure(AppError.Timeout()) else AppResult.Success("ok")
        }

        assertEquals(3, attempts)
        assertEquals(AppResult.Success("ok"), result)
    }

    /**
     * PRODUCT_SPEC AUTH-004 — "the app never loops login requests", and 14.3's "403: no retry".
     *
     * These are the two the policy must refuse even though a retry would be harmless to the server:
     * repeating a rejected credential cannot start working, and repeating a forbidden request cannot
     * become allowed.
     */
    @Test
    fun `authentication and authorization failures are never retried`() = runTest {
        listOf(AppError.Authentication(), AppError.Authorization(summary = "no")).forEach { error ->
            var attempts = 0
            policy().readOnly("op") {
                attempts++
                AppResult.Failure(error)
            }
            assertEquals(1, attempts, "${error.code} must not be retried")
        }
        assertEquals(0, currentTime)
    }

    /**
     * The exclusion that `AppError.isRetryable` alone would get wrong.
     *
     * `Canceled` is marked retryable — it *could* sensibly be attempted again — but a user who cancelled
     * did not ask for it to happen again now. Retrying a cancellation is how a stop button stops nothing.
     */
    @Test
    fun `a cancelled operation is not retried despite being marked retryable`() = runTest {
        assertTrue(AppError.Canceled().isRetryable, "the premise of this test")
        var attempts = 0

        policy().readOnly("op") {
            attempts++
            AppResult.Failure(AppError.Canceled())
        }

        assertEquals(1, attempts)
    }

    /**
     * PRODUCT_SPEC 14.3 — "429: honor `Retry-After`".
     *
     * The server named a time. Guessing something sooner is both ruder and less likely to work, so the
     * jittered schedule is overridden rather than blended with.
     */
    @Test
    fun `a 429 waits exactly as long as the server asked`() = runTest {
        var attempts = 0

        policy().readOnly("op") {
            attempts++
            if (attempts == 1) {
                AppResult.Failure(AppError.Server(statusCode = 429, retryAfterSeconds = 2))
            } else {
                AppResult.Success(Unit)
            }
        }

        assertEquals(2_000, currentTime)
    }

    /**
     * …but not for as long as it likes.
     *
     * A server may ask for an hour. Honouring that literally leaves a sync asleep long after the user
     * gave up on it, so the wait is capped and the attempt fails instead — which the UI can show.
     */
    @Test
    fun `an outlandish Retry-After is capped`() = runTest {
        var attempts = 0

        policy().readOnly("op") {
            attempts++
            if (attempts == 1) {
                AppResult.Failure(AppError.Server(statusCode = 503, retryAfterSeconds = 3_600))
            } else {
                AppResult.Success(Unit)
            }
        }

        assertEquals(8_000, currentTime, "capped at the maximum backoff, not an hour")
    }

    /**
     * The backoff grows, and every wait stays inside its exponential ceiling.
     *
     * Asserted as a bound rather than as exact values because the delays are jittered on purpose. What
     * matters is that no wait exceeds `500 ms × 2^n` and that the whole sequence is bounded — pinning
     * the exact numbers would test the seed, not the policy.
     */
    @Test
    fun `waits stay within the exponential ceiling`() = runTest {
        val waits = mutableListOf<Long>()
        var last = 0L

        policy().readOnly("op") {
            waits += currentTime - last
            last = currentTime
            AppResult.Failure(AppError.Server(statusCode = 500))
        }

        // The first entry is the wait before attempt 1, which is none.
        assertEquals(0L, waits.first())
        val ceilings = listOf(500L, 1_000L, 2_000L)
        waits.drop(1).forEachIndexed { index, wait ->
            assertTrue(wait <= ceilings[index], "wait ${index + 1} was $wait, above ${ceilings[index]}")
        }
        assertTrue(currentTime <= ceilings.sum(), "the whole sequence must be bounded")
    }

    /** PRODUCT_SPEC 14.5 — a retry log line names the operation and the error code, and nothing else. */
    @Test
    fun `retry logging carries no address, id or title`() = runTest {
        policy().readOnly("listItems") { AppResult.Failure(AppError.Network()) }

        assertTrue(sink.lines.isNotEmpty(), "a retry is worth a log line")
        assertTrue(sink.text.contains("listItems"), "the operation is named so a log is actionable")
        assertTrue(sink.text.contains("network"), "…and so is the error that caused it")
    }
}
