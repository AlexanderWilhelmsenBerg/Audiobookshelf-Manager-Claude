package com.example.shelfplayer.core.common.time

import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.LogLevel
import com.example.shelfplayer.core.common.log.LogSink
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-005 — the clock-skew reading.
 *
 * A local sink rather than `:core:testing`'s, for the reason `RedactingLoggerTest` records: that module
 * depends on this one.
 */
class ServerClockTest {

    private class TestSink : LogSink {
        val lines = mutableListOf<Pair<LogLevel, String>>()
        val text: String get() = lines.joinToString(separator = "\n") { it.second }

        override fun write(level: LogLevel, tag: String, line: String) {
            lines += level to line
        }
    }

    private val sink = TestSink()
    private val clock = ServerClock(RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default)))

    @Test
    fun `there is no reading before the app has talked to a server`() {
        assertNull(clock.skew.value)
    }

    /** Positive means this device is *behind* the server, which is the direction the doc claims. */
    @Test
    fun `a device behind the server reports a positive offset`() {
        clock.record(serverTime = Instant.ofEpochSecond(1_000), deviceTime = Instant.ofEpochSecond(940))

        assertEquals(60.seconds, clock.skew.value?.offset)
    }

    @Test
    fun `a device ahead of the server reports a negative offset`() {
        clock.record(serverTime = Instant.ofEpochSecond(940), deviceTime = Instant.ofEpochSecond(1_000))

        assertEquals((-60).seconds, clock.skew.value?.offset)
    }

    /**
     * The reading includes the round trip, so agreement is not exact agreement.
     *
     * A few hundred milliseconds on a slow connection means "these clocks agree"; PLAY-005's threshold is five
     * *minutes* because the question is whether the device is wrong enough to corrupt a conflict.
     */
    @Test
    fun `a round-trip-sized difference is not significant`() {
        clock.record(serverTime = Instant.ofEpochMilli(1_000_400), deviceTime = Instant.ofEpochMilli(1_000_000))

        assertFalse(clock.skew.value!!.isSignificant)
    }

    @Test
    fun `exactly five minutes is not yet significant and a second more is`() {
        clock.record(
            serverTime = Instant.ofEpochSecond(0).plusMillis(5.minutes.inWholeMilliseconds),
            deviceTime = Instant.ofEpochSecond(0),
        )
        assertFalse(clock.skew.value!!.isSignificant)

        clock.record(
            serverTime = Instant.ofEpochSecond(1).plusMillis(5.minutes.inWholeMilliseconds),
            deviceTime = Instant.ofEpochSecond(0),
        )
        assertTrue(clock.skew.value!!.isSignificant)
    }

    /** Significant in either direction: a device five minutes fast wins conflicts it should lose. */
    @Test
    fun `a device far ahead of the server is significant too`() {
        clock.record(serverTime = Instant.ofEpochSecond(0), deviceTime = Instant.ofEpochSecond(3_600))

        assertTrue(clock.skew.value!!.isSignificant)
    }

    /** Latest wins, so correcting the device clock is reflected immediately rather than averaged away. */
    @Test
    fun `a corrected clock is reported on the next reading`() {
        clock.record(serverTime = Instant.ofEpochSecond(3_600), deviceTime = Instant.ofEpochSecond(0))
        assertTrue(clock.skew.value!!.isSignificant)

        clock.record(serverTime = Instant.ofEpochSecond(3_600), deviceTime = Instant.ofEpochSecond(3_600))

        assertFalse(clock.skew.value!!.isSignificant)
        assertEquals(Instant.ofEpochSecond(3_600), clock.skew.value?.measuredAt)
    }

    /**
     * This runs on every HTTP exchange, so the warning is per *crossing* rather than per response.
     *
     * A warning per request would be its own problem — and would bury the one line that says the clock is
     * wrong under a thousand identical ones.
     */
    @Test
    fun `crossing the threshold warns once rather than on every reading`() {
        repeat(3) {
            clock.record(serverTime = Instant.ofEpochSecond(3_600), deviceTime = Instant.ofEpochSecond(0))
        }

        assertEquals(1, sink.lines.count { it.first == LogLevel.Warn })
    }

    @Test
    fun `recrossing the threshold warns again`() {
        clock.record(serverTime = Instant.ofEpochSecond(3_600), deviceTime = Instant.ofEpochSecond(0))
        clock.record(serverTime = Instant.ofEpochSecond(3_600), deviceTime = Instant.ofEpochSecond(3_600))
        clock.record(serverTime = Instant.ofEpochSecond(7_200), deviceTime = Instant.ofEpochSecond(0))

        assertEquals(2, sink.lines.count { it.first == LogLevel.Warn })
    }

    /**
     * PRODUCT_SPEC 14.5 — the offset reaches the log and the timestamps do not.
     *
     * A server time plus a device time is a fingerprint of when and where somebody was listening; the
     * difference between them is the only part a support report needs.
     */
    @Test
    fun `the warning carries the offset and neither timestamp`() {
        clock.record(serverTime = Instant.ofEpochSecond(3_600), deviceTime = Instant.ofEpochSecond(0))

        val warning = sink.lines.single { it.first == LogLevel.Warn }.second
        assertTrue(warning.contains("offset=3600000ms"), warning)
        assertFalse(warning.contains("1970"), warning)
    }
}
