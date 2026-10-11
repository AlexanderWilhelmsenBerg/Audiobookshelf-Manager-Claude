package com.example.shelfplayer.auto

import coil.request.CachePolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #250: a car must be able to load profile-B artwork even when only profile-A covers were cached. */
class AutoArtworkFetchPolicyTest {

    @Test
    fun `cached cover stays offline and never asks for network`() = runTest {
        val requests = mutableListOf<CachePolicy>()
        val loaded = AutoArtworkFetchPolicy().load(listOf("cover")) { _, policy ->
            requests += policy
            if (policy == CachePolicy.DISABLED) "cached" else "network"
        }

        assertEquals("cached", loaded?.value)
        assertEquals(AutoArtworkFetchMode.Cache, loaded?.mode)
        assertEquals(listOf(CachePolicy.DISABLED), requests)
    }

    @Test
    fun `new profile cold cover fetches via authenticated loader after cache miss`() = runTest {
        val requests = mutableListOf<Pair<String, CachePolicy>>()
        val loaded = AutoArtworkFetchPolicy().load(listOf("profile-b-cover")) { source, policy ->
            requests += source to policy
            if (policy == CachePolicy.ENABLED) "fresh-b-cover" else null
        }

        assertEquals("fresh-b-cover", loaded?.value)
        assertEquals(AutoArtworkFetchMode.Network, loaded?.mode)
        assertEquals(
            listOf(
                "profile-b-cover" to CachePolicy.DISABLED,
                "profile-b-cover" to CachePolicy.ENABLED,
            ),
            requests,
        )
    }

    @Test
    fun `downloaded or cached fallback source wins before network requests`() = runTest {
        val requests = mutableListOf<Pair<String, CachePolicy>>()
        val loaded = AutoArtworkFetchPolicy().load(listOf("missing-local", "cached-remote")) { source, policy ->
            requests += source to policy
            if (source == "cached-remote" && policy == CachePolicy.DISABLED) "cached-remote-bytes" else null
        }

        assertEquals("cached-remote-bytes", loaded?.value)
        assertEquals(AutoArtworkFetchMode.Cache, loaded?.mode)
        assertEquals(2, requests.size)
        assertEquals(true, requests.all { (_, policy) -> policy == CachePolicy.DISABLED })
    }

    @Test
    fun `missing cover returns no image instead of leaking another source`() = runTest {
        val loaded = AutoArtworkFetchPolicy().load(listOf("missing")) { _, _ -> null as String? }
        assertNull(loaded)
    }

    @Test
    fun `concurrent cold covers cannot exceed network fetch limit`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val policy = AutoArtworkFetchPolicy(maxConcurrentFetches = 1)
        var active = 0
        var peak = 0
        val first = async {
            policy.load(listOf("a")) { _, mode ->
                if (mode == CachePolicy.DISABLED) {
                    null
                } else {
                    active++
                    peak = maxOf(peak, active)
                    gate.await()
                    active--
                    "a"
                }
            }
        }
        val second = async {
            policy.load(listOf("b")) { _, mode ->
                if (mode == CachePolicy.DISABLED) {
                    null
                } else {
                    active++
                    peak = maxOf(peak, active)
                    "b"
                }
            }
        }

        runCurrent()
        assertEquals(1, peak)
        gate.complete(Unit)
        runCurrent()
        assertEquals("a", first.await()?.value)
        assertEquals("b", second.await()?.value)
        assertEquals(1, peak)
    }

    @Test
    fun `network timeout frees the slot and falls back to host placeholder`() = runTest {
        val policy = AutoArtworkFetchPolicy(maxConcurrentFetches = 1, fetchMillis = 50)
        val loaded = policy.load(listOf("slow")) { _, mode ->
            if (mode == CachePolicy.DISABLED) {
                null
            } else {
                kotlinx.coroutines.delay(1_000)
                "late"
            }
        }
        assertNull(loaded)
        assertEquals(
            "ok",
            policy.load(listOf("cached")) { _, mode ->
                if (mode == CachePolicy.DISABLED) {
                    "ok"
                } else {
                    null
                }
            }?.value,
        )
    }
}
