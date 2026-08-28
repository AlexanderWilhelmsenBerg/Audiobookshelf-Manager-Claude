package com.example.shelfplayer.feature.settings

import com.example.shelfplayer.core.common.log.LogLevel
import com.example.shelfplayer.core.common.log.LoggedEvent

/**
 * PRODUCT_SPEC 14.4 — which of the buffered lines the event log is currently showing.
 *
 * ### Why this is a value and a pure function rather than state in the view model
 *
 * `EventLogViewModel`'s KDoc argued against filtering there, and the argument was right for the reason it
 * gave: *"a log with a view model that filters or paginates is a log that can disagree with itself about
 * what happened."* That stays true. The buffer is still the whole truth, the view model still hands it over
 * untouched, and this narrows a **copy** of it for display.
 *
 * Keeping the rule here rather than inline in the sheet is the same shape as `ControllerTrust.accessFor` and
 * `TrackDurations.recovered`: a decision a JVM test can exercise, in a module whose UI a JVM test can only
 * render. Every branch below is a branch `EventLogFilterTest` names.
 *
 * ### Empty means all, deliberately
 *
 * A filter with nothing selected shows everything, rather than nothing. The alternative reads as a bug the
 * first time somebody clears the last chip and the list empties — and "no levels selected" and "all levels
 * selected" are the same intent expressed two ways.
 *
 * @property text matched against the rendered line **and** the category, case-insensitively. Against both
 *   because a person typing `playback` means the category and a person typing `refused` means the message,
 *   and asking them which is a question the app can answer for itself.
 * @property levels which severities to show. Empty shows all.
 * @property categories which categories to show, by tag — `Playback`, `Sync`, `Auth`. Empty shows all.
 */
internal data class EventLogQuery(
    val text: String = "",
    val levels: Set<LogLevel> = emptySet(),
    val categories: Set<String> = emptySet(),
) {
    /** Whether anything is hidden, so the sheet can say so rather than leaving a short list unexplained. */
    val isNarrowed: Boolean get() = text.isNotBlank() || levels.isNotEmpty() || categories.isNotEmpty()
}

internal object EventLogFilter {

    /**
     * The events [query] selects, in the order given.
     *
     * The caller reverses for display; this deliberately does not, because a filter that also reorders is
     * two behaviours behind one name and the sheet already owns the newest-first decision.
     */
    fun apply(events: List<LoggedEvent>, query: EventLogQuery): List<LoggedEvent> {
        val needle = query.text.trim().lowercase()
        return events.filter { event ->
            (query.levels.isEmpty() || event.level in query.levels) &&
                (query.categories.isEmpty() || event.tag in query.categories) &&
                (needle.isEmpty() || event.matches(needle))
        }
    }

    /**
     * The categories present in [events], in the order they first appear.
     *
     * Derived from the buffer rather than from `LogCategory.entries`, and that is the useful choice: a chip
     * for a category nothing has logged is a chip that always yields an empty list. Ordered by first
     * appearance rather than alphabetically so the chips stay put as lines arrive, instead of re-sorting
     * under a finger mid-scroll.
     */
    fun categoriesIn(events: List<LoggedEvent>): List<String> = events.map(LoggedEvent::tag).distinct()

    /**
     * The levels present in [events], **in severity order** rather than in order of appearance.
     *
     * The opposite choice from [categoriesIn], on purpose: severity has a natural order that a reader
     * already knows, and `Error` appearing before `Info` because it happened first would read as a mistake.
     * Categories have no such order, so appearance is the least surprising one available.
     */
    fun levelsIn(events: List<LoggedEvent>): List<LogLevel> {
        val present = events.mapTo(mutableSetOf(), LoggedEvent::level)
        return LogLevel.entries.filter { it in present }
    }

    private fun LoggedEvent.matches(needle: String): Boolean =
        line.lowercase().contains(needle) || tag.lowercase().contains(needle)
}
