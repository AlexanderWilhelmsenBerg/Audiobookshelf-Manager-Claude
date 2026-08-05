package com.example.shelfplayer.core.common.time

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * PRODUCT_SPEC 16.3 — domain logic never calls `System.currentTimeMillis()` directly.
 *
 * Two clocks are exposed because they answer different questions:
 *  - [now] is wall-clock time, used for `remoteUpdatedAt`/`lastFetchedAt` and for the clock-skew
 *    detection PRODUCT_SPEC PLAY-005 requires. It can jump backwards when the device time changes.
 *  - [elapsed] is a monotonic reading, used for timers, backoff and playback cadence. It never jumps.
 *
 * Using wall-clock time for a sleep timer is a real defect (a network time sync mid-book would fire
 * it early or late), which is why both are on the injected interface rather than one convenience
 * method.
 */
interface AppClock {
    fun now(): Instant

    fun elapsed(): Duration
}

@Singleton
class SystemAppClock @Inject constructor() : AppClock {
    override fun now(): Instant = Instant.now()

    @Suppress("ForbiddenMethodCall")
    override fun elapsed(): Duration = System.nanoTime().nanoseconds
}
