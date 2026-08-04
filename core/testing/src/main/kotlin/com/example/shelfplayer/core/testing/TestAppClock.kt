package com.example.shelfplayer.core.testing

import com.example.shelfplayer.core.common.time.AppClock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * A clock a test fully controls.
 *
 * Wall-clock time and the monotonic reading advance independently so tests can reproduce the exact
 * situation PRODUCT_SPEC PLAY-005 cares about: a device whose wall clock jumps while elapsed time
 * keeps running.
 */
class TestAppClock(
    private var instant: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    private var monotonic: Duration = ZERO,
) : AppClock {
    override fun now(): Instant = instant

    override fun elapsed(): Duration = monotonic

    /** Advances both readings, the ordinary case. */
    fun advanceBy(duration: Duration) {
        instant = instant.plusNanos(duration.inWholeNanoseconds)
        monotonic += duration
    }

    /** Moves wall-clock time only, simulating an NTP correction or a user changing the date. */
    fun setWallClock(newInstant: Instant) {
        instant = newInstant
    }
}
