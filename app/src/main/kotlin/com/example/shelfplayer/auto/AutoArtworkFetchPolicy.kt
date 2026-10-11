package com.example.shelfplayer.auto

import coil.request.CachePolicy
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounded cold-cache fallback for the existing profile-scoped Android Auto content provider.
 *
 * Always try the shared Coil cache first, including downloaded local covers. A miss is then allowed to
 * fetch through the same authenticated ImageLoader as phone artwork. Only [MAX_FETCHES] HTTP fetch
 * attempts can be in flight, and both waiting and fetching have deadlines: a car host's openFile callback
 * must not block indefinitely or launch an unbounded flood during a profile switch.
 *
 * The provider checks profile ownership before and after this operation; the policy never owns browse
 * state, stores a second image cache or exposes source URLs to the host.
 */
internal class AutoArtworkFetchPolicy(
    maxConcurrentFetches: Int = MAX_FETCHES,
    private val waitMillis: Long = WAIT_MILLIS,
    private val fetchMillis: Long = FETCH_MILLIS,
) {
    private val networkSlots = Semaphore(maxConcurrentFetches)

    suspend fun <T : Any> load(
        sources: List<String>,
        fetch: suspend (String, CachePolicy) -> T?,
    ): AutoArtworkFetch<T>? {
        sources.firstNotNullOfOrNull { source -> fetch(source, CachePolicy.DISABLED) }
            ?.let { return AutoArtworkFetch(it, AutoArtworkFetchMode.Cache) }

        // Timeouts cancel the Coil request and release the slot even if another profile becomes active.
        val acquired = withTimeoutOrNull(waitMillis) {
            networkSlots.acquire()
            true
        } ?: return null

        try {
            return withTimeoutOrNull(fetchMillis) {
                sources.firstNotNullOfOrNull { source -> fetch(source, CachePolicy.ENABLED) }
                    ?.let { AutoArtworkFetch(it, AutoArtworkFetchMode.Network) }
            }
        } finally {
            if (acquired) networkSlots.release()
        }
    }

    private companion object {
        const val MAX_FETCHES = 4
        const val WAIT_MILLIS = 10_000L
        const val FETCH_MILLIS = 10_000L
    }
}

internal data class AutoArtworkFetch<T : Any>(val value: T, val mode: AutoArtworkFetchMode)

internal enum class AutoArtworkFetchMode(val code: String) {
    Cache("cache"),
    Network("network"),
}
