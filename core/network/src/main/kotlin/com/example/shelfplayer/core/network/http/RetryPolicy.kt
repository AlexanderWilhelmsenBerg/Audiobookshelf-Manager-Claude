package com.example.shelfplayer.core.network.http

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.random.Random

/**
 * PRODUCT_SPEC 14.3 — the retry policy, which until now existed only as a property on [AppError].
 *
 * `isRetryable` and `retryAfterSeconds` were computed on every error and read by nothing. A 490-item
 * library sync is 491 requests with no resilience at all, which is why a device run reported a single
 * transient failure as a partially synced library.
 *
 * ### What is retried, and what is emphatically not
 *
 * Only read-only operations, and only errors [AppError.isRetryable] admits: network faults, timeouts,
 * 5xx, and `429`. A `401` is not retried — PRODUCT_SPEC AUTH-004 says "the app never loops login
 * requests" — and neither is a `403`, which PRODUCT_SPEC 14.3 answers with a permission refresh rather
 * than another attempt at the same rejected question.
 *
 * [AppError.Canceled] is excluded despite being marked retryable, and that exclusion is the interesting
 * one: the flag means "this operation could sensibly be attempted again", which is true, but a user who
 * cancelled something did not ask for it to be attempted again *now*. Retrying a cancellation is how a
 * stop button stops nothing.
 *
 * ### Full jitter, not fixed backoff
 *
 * The delay is a random value in `[0, base × 2^n]` rather than the ceiling itself. With a fixed
 * schedule, every request that failed in the same outage retries in the same instant, and a server that
 * fell over under load is handed the same load again the moment it recovers. Spreading the retries is
 * the point; the exponential part only bounds how far they spread.
 *
 * A server that answers `429` overrides all of this with its own `Retry-After`: it has said when to come
 * back, and guessing something sooner is both ruder and less likely to work.
 */
@Singleton
class RetryPolicy @Inject constructor(private val logger: Logger, private val random: Random) {

    /**
     * Runs [block], retrying a retryable failure up to [MAX_RETRIES] times.
     *
     * [operation] is a constant chosen at the call site — never a URL, an id or a title — because it is
     * logged (PRODUCT_SPEC 14.5).
     *
     * Cancellation propagates: the wait is `delay`, so a cancelled scope stops here rather than serving
     * out its backoff first.
     */
    suspend fun <T> readOnly(operation: String, block: suspend () -> AppResult<T>): AppResult<T> {
        var attempt = 0
        while (true) {
            val result = block()
            if (result !is AppResult.Failure) return result

            val error = result.error
            if (attempt >= MAX_RETRIES || !error.isWorthRetrying()) return result

            val wait = waitFor(attempt, error)
            logger.info(
                LogCategory.Sync,
                "Retrying a failed read",
                LogField.Public("operation", operation),
                LogField.Public("errorCode", error.code),
                LogField.Count("attempt", attempt + 1),
                LogField.Count("waitMillis", wait.toInt()),
            )
            delay(wait)
            attempt++
        }
    }

    /**
     * [AppError.isRetryable] minus cancellation. See the class comment: the flag answers "could this be
     * attempted again", and here the question is "should it be, right now, without being asked".
     */
    private fun AppError.isWorthRetrying(): Boolean = isRetryable && this !is AppError.Canceled

    private fun waitFor(attempt: Int, error: AppError): Long {
        val serverAsked = (error as? AppError.Server)?.retryAfterSeconds
        if (serverAsked != null) return min(serverAsked * MILLIS_PER_SECOND, MAX_BACKOFF_MILLIS)

        val ceiling = min(BASE_BACKOFF_MILLIS shl attempt, MAX_BACKOFF_MILLIS)
        return random.nextLong(ceiling + 1)
    }

    private companion object {
        /** PRODUCT_SPEC 14.3 — "up to 3 retries for transient network/5xx errors". */
        const val MAX_RETRIES = 3
        const val BASE_BACKOFF_MILLIS = 500L

        /**
         * The cap applies to a server-supplied `Retry-After` too.
         *
         * A server may ask for an hour. Honouring that literally would leave a sync silently asleep long
         * after the user gave up on it, so the wait is bounded and the attempt simply fails instead —
         * which the UI can show, and the user can act on.
         */
        const val MAX_BACKOFF_MILLIS = 8_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
