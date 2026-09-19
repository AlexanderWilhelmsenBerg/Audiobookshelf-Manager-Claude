package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.SleepTimerScheduleSettings
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BW-SLEEP-01 — portable local-wall-clock policy for automatic sleep-timer eligibility.
 *
 * This object owns no countdown. It answers which civil schedule occurrence, if any, contains an instant
 * and when the next start boundary occurs. The existing SleepTimerController remains the only object
 * allowed to create and count down a timer.
 *
 * DST is explicit rather than delegated to an incidental library default:
 * - a boundary in a spring-forward gap resolves to the first valid instant after the gap;
 * - an overlapping start resolves to the earlier instant;
 * - an overlapping end resolves to the later instant.
 *
 * The overlap rule keeps the whole repeated civil interval eligible. start == end is an empty window,
 * never an undocumented 24-hour schedule.
 */
internal object SleepSchedulePolicy {
    data class Occurrence(val id: String, val start: Instant, val end: Instant)

    fun currentOccurrence(now: Instant, zone: ZoneId, settings: SleepTimerScheduleSettings): Occurrence? {
        if (!settings.enabled || settings.start == settings.end) return null
        val localDate = now.atZone(zone).toLocalDate()
        return sequenceOf(localDate.minusDays(1), localDate)
            .mapNotNull { occurrence(it, zone, settings) }
            .firstOrNull { !now.isBefore(it.start) && now.isBefore(it.end) }
    }

    fun nextStart(now: Instant, zone: ZoneId, settings: SleepTimerScheduleSettings): Instant? {
        if (!settings.enabled || settings.start == settings.end) return null
        val localDate = now.atZone(zone).toLocalDate()
        return (0L..LOOKAHEAD_DAYS)
            .asSequence()
            .map { localDate.plusDays(it) }
            .map { resolve(it, settings.start, zone, Boundary.Start).toInstant() }
            .firstOrNull { it.isAfter(now) }
    }

    private fun occurrence(startDate: LocalDate, zone: ZoneId, settings: SleepTimerScheduleSettings): Occurrence? {
        val endDate = if (settings.start < settings.end) startDate else startDate.plusDays(1)
        val start = resolve(startDate, settings.start, zone, Boundary.Start).toInstant()
        val end = resolve(endDate, settings.end, zone, Boundary.End).toInstant()
        if (!end.isAfter(start)) return null
        return Occurrence(
            id = occurrenceId(startDate, settings),
            start = start,
            end = end,
        )
    }

    private fun occurrenceId(startDate: LocalDate, settings: SleepTimerScheduleSettings): String = buildString {
        append(startDate)
        append('|')
        append(settings.start.toSecondOfDay() / SECONDS_PER_MINUTE)
        append('|')
        append(settings.end.toSecondOfDay() / SECONDS_PER_MINUTE)
    }

    private fun resolve(date: LocalDate, time: java.time.LocalTime, zone: ZoneId, boundary: Boundary): ZonedDateTime {
        val local = LocalDateTime.of(date, time)
        val rules = zone.rules
        val offsets = rules.getValidOffsets(local)
        return when {
            offsets.size == 1 -> ZonedDateTime.ofLocal(local, zone, offsets.single())

            offsets.isEmpty() -> {
                val transition = checkNotNull(rules.getTransition(local)) {
                    "A local time with no valid offset must belong to a zone transition"
                }
                ZonedDateTime.ofLocal(transition.dateTimeAfter, zone, transition.offsetAfter)
            }

            else -> {
                val chosen = when (boundary) {
                    Boundary.Start -> offsets.minBy { local.toInstant(it) }
                    Boundary.End -> offsets.maxBy { local.toInstant(it) }
                }
                ZonedDateTime.ofLocal(local, zone, chosen)
            }
        }
    }

    private enum class Boundary { Start, End }

    private const val SECONDS_PER_MINUTE = 60
    private const val LOOKAHEAD_DAYS = 3L
}

interface LocalZoneProvider {
    fun current(): ZoneId
}

/** Android/process boundary for the civil zone; tests provide a deterministic implementation. */
@Singleton
internal class SystemLocalZoneProvider @Inject constructor() : LocalZoneProvider {
    override fun current(): ZoneId = ZoneId.systemDefault()
}
