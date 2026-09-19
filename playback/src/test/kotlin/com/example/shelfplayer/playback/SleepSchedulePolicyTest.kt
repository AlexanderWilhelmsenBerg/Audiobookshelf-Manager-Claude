package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.SleepTimerScheduleSettings
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class SleepSchedulePolicyTest {

    @Test
    fun `disabled schedule is never eligible`() {
        val schedule = schedule(enabled = false, start = "22:00", end = "06:00")
        assertNull(SleepSchedulePolicy.currentOccurrence(instant("2026-09-19T23:00:00Z"), ZoneOffset.UTC, schedule))
        assertNull(SleepSchedulePolicy.nextStart(instant("2026-09-19T23:00:00Z"), ZoneOffset.UTC, schedule))
    }

    @Test
    fun `same-day window distinguishes before inside and after`() {
        val schedule = schedule(start = "13:00", end = "15:00")

        assertNull(SleepSchedulePolicy.currentOccurrence(instant("2026-09-19T12:59:59Z"), ZoneOffset.UTC, schedule))
        assertNotNull(SleepSchedulePolicy.currentOccurrence(instant("2026-09-19T13:00:00Z"), ZoneOffset.UTC, schedule))
        assertNotNull(SleepSchedulePolicy.currentOccurrence(instant("2026-09-19T14:59:59Z"), ZoneOffset.UTC, schedule))
        assertNull(SleepSchedulePolicy.currentOccurrence(instant("2026-09-19T15:00:00Z"), ZoneOffset.UTC, schedule))
    }

    @Test
    fun `overnight window assigns after-midnight time to previous start date`() {
        val schedule = schedule(start = "22:00", end = "06:00")

        val late = assertNotNull(
            SleepSchedulePolicy.currentOccurrence(instant("2026-09-19T23:30:00Z"), ZoneOffset.UTC, schedule),
        )
        val early = assertNotNull(
            SleepSchedulePolicy.currentOccurrence(instant("2026-09-20T05:55:00Z"), ZoneOffset.UTC, schedule),
        )
        assertEquals(late.id, early.id)
        assertEquals("2026-09-19|1320|360", late.id)
        assertNull(SleepSchedulePolicy.currentOccurrence(instant("2026-09-20T06:00:00Z"), ZoneOffset.UTC, schedule))
    }

    @Test
    fun `next start is the next civil occurrence not the window end`() {
        val schedule = schedule(start = "22:00", end = "06:00")
        assertEquals(
            instant("2026-09-20T22:00:00Z"),
            SleepSchedulePolicy.nextStart(instant("2026-09-20T05:55:00Z"), ZoneOffset.UTC, schedule),
        )
    }

    @Test
    fun `start equal end is explicitly an empty window`() {
        val schedule = schedule(start = "22:00", end = "22:00")
        assertNull(SleepSchedulePolicy.currentOccurrence(instant("2026-09-19T22:00:00Z"), ZoneOffset.UTC, schedule))
        assertNull(SleepSchedulePolicy.nextStart(instant("2026-09-19T12:00:00Z"), ZoneOffset.UTC, schedule))
    }

    @Test
    fun `timezone change recomputes eligibility from the new local wall clock`() {
        val schedule = schedule(start = "22:00", end = "06:00")
        val now = instant("2026-09-19T21:30:00Z")

        assertNull(SleepSchedulePolicy.currentOccurrence(now, ZoneOffset.UTC, schedule))
        assertNotNull(SleepSchedulePolicy.currentOccurrence(now, ZoneId.of("Europe/Oslo"), schedule))
    }

    @Test
    fun `spring gap start resolves to first valid instant after the gap`() {
        val oslo = ZoneId.of("Europe/Oslo")
        val schedule = schedule(start = "02:30", end = "04:00")

        val occurrence = assertNotNull(
            SleepSchedulePolicy.currentOccurrence(instant("2026-03-29T01:05:00Z"), oslo, schedule),
        )
        assertEquals(instant("2026-03-29T01:00:00Z"), occurrence.start)
        assertEquals(instant("2026-03-29T02:00:00Z"), occurrence.end)
    }

    @Test
    fun `fall overlap uses earlier start and later end to keep repeated civil interval eligible`() {
        val oslo = ZoneId.of("Europe/Oslo")
        val schedule = schedule(start = "02:30", end = "02:45")

        val first = assertNotNull(
            SleepSchedulePolicy.currentOccurrence(instant("2026-10-25T00:35:00Z"), oslo, schedule),
        )
        val repeated = assertNotNull(
            SleepSchedulePolicy.currentOccurrence(instant("2026-10-25T01:35:00Z"), oslo, schedule),
        )
        assertEquals(first.id, repeated.id)
        assertEquals(instant("2026-10-25T00:30:00Z"), first.start)
        assertEquals(instant("2026-10-25T01:45:00Z"), first.end)
    }

    private fun schedule(
        enabled: Boolean = true,
        start: String,
        end: String,
    ) = SleepTimerScheduleSettings(
        enabled = enabled,
        start = LocalTime.parse(start),
        end = LocalTime.parse(end),
        suppressedOccurrence = null,
        replayRequiredOccurrence = null,
    )

    private fun instant(value: String): Instant = Instant.parse(value)
}
