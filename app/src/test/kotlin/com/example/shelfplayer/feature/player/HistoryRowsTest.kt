package com.example.shelfplayer.feature.player

import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.PlaybackHistoryEntry
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * PRODUCT_SPEC PLAY-003 — the history pane's day headings.
 *
 * A plain JVM test, deliberately: the grouping is the one part of the pane with a decision in it, and
 * pulling it out of the composable is what lets it be tested without a window. The rest of the sheet is
 * icons and strings.
 *
 * Everything here is asserted in a **named zone**. A day boundary is a property of the reader's clock, and
 * a test that used the machine's own would pass in Oslo and fail in CI.
 */
class HistoryRowsTest {

    /** Newest first, and a heading above each new day rather than above each row. */
    @Test
    fun `each day gets one heading`() {
        val rows = rowsFor(
            listOf(
                entry("a", at("2026-08-13T21:10")),
                entry("b", at("2026-08-13T20:40")),
                entry("c", at("2026-08-12T23:55")),
            ),
            zone = OSLO,
        )

        assertEquals(
            listOf("day-2026-08-13", "a", "b", "day-2026-08-12", "c"),
            rows.map { it.key },
        )
    }

    /**
     * The boundary that only a zone can decide.
     *
     * `22:30 UTC` and `00:30 UTC` are the same evening in Oslo — half past midnight is still the fourteenth
     * there, but the two instants are ninety minutes and one calendar day apart. Grouped in UTC these two
     * rows would sit under different headings; grouped in the reader's zone they sit under the day each
     * actually happened on, which is the whole point of a heading.
     */
    @Test
    fun `days are counted in the reader's own zone`() {
        val evening = Instant.parse("2026-08-13T22:30:00Z")
        val afterMidnight = Instant.parse("2026-08-14T00:30:00Z")

        val osloKeys = rowsFor(listOf(entry("late", afterMidnight), entry("early", evening)), OSLO).map { it.key }
        val utcKeys = rowsFor(listOf(entry("late", afterMidnight), entry("early", evening)), ZoneId.of("UTC"))
            .map { it.key }

        assertEquals(listOf("day-2026-08-14", "late", "early"), osloKeys, "one Oslo day: the 14th")
        assertEquals(listOf("day-2026-08-14", "late", "day-2026-08-13", "early"), utcKeys, "two UTC days")
    }

    @Test
    fun `an empty history has no headings`() {
        assertEquals(emptyList(), rowsFor(emptyList(), OSLO))
    }

    private fun at(local: String): Instant = ZonedDateTime.of(java.time.LocalDateTime.parse(local), OSLO).toInstant()

    private fun entry(id: String, at: Instant) = PlaybackHistoryEntry(
        id = id,
        event = PlaybackEvent.Seek,
        from = 10.minutes,
        to = 20.minutes,
        detail = null as Duration?,
        at = at,
    )

    private companion object {
        val OSLO: ZoneId = ZoneId.of("Europe/Oslo")
    }
}
