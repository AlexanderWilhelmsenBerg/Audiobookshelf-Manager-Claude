package com.example.shelfplayer.feature.settings

import com.example.shelfplayer.core.common.log.LogLevel
import com.example.shelfplayer.core.common.log.LoggedEvent
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC 14.4 — narrowing the event log without lying about what is in it.
 *
 * The sheet used to offer one toggle, *Problems only*, which answered "what broke" and nothing else.
 * Finding one line about downloads among four hundred about playback meant scrolling a phone screen. This
 * covers the rule that replaced it: free text, a level set and a category set, each of which can be empty.
 *
 * A JVM test rather than a rendered one because the rule is the part worth pinning — `EventLogSheet` owns
 * the chips and the order, and a Compose test of those would assert that `FilterChip` works.
 */
class EventLogFilterTest {

    // ------------------------------------------------------------------ the default

    /**
     * **Empty means all**, and this is the assertion that keeps it that way.
     *
     * The opposite reading is defensible right up until somebody clears the last chip and the list empties,
     * at which point it reads as a bug. "No levels selected" and "all levels selected" are one intent said
     * two ways.
     */
    @Test
    fun `an empty query shows everything`() {
        val events = sample()

        assertEquals(events, EventLogFilter.apply(events, EventLogQuery()))
        assertFalse(EventLogQuery().isNarrowed)
    }

    // ------------------------------------------------------------------ the three axes

    @Test
    fun `a level filter keeps only those levels`() {
        val shown = EventLogFilter.apply(sample(), EventLogQuery(levels = setOf(LogLevel.Warn, LogLevel.Error)))

        assertEquals(listOf(LogLevel.Warn, LogLevel.Error), shown.map { it.level })
    }

    @Test
    fun `a category filter keeps only that area`() {
        val shown = EventLogFilter.apply(sample(), EventLogQuery(categories = setOf("Download")))

        assertEquals(listOf("Download"), shown.map { it.tag })
    }

    /**
     * Search matches the **message and the category**, because a person typing `download` might mean either
     * and the app can answer that for itself rather than asking which they meant.
     */
    @Test
    fun `search matches the message or the category`() {
        val byMessage = EventLogFilter.apply(sample(), EventLogQuery(text = "refused"))
        val byCategory = EventLogFilter.apply(sample(), EventLogQuery(text = "download"))

        assertEquals(listOf("A controller was refused"), byMessage.map { it.line })
        assertEquals(listOf("Download"), byCategory.map { it.tag })
    }

    @Test
    fun `search ignores case and surrounding spaces`() {
        val shown = EventLogFilter.apply(sample(), EventLogQuery(text = "  REFUSED "))

        assertEquals(1, shown.size)
    }

    /** The three axes are an AND, not an OR — a narrowing tool that widened would be useless. */
    @Test
    fun `the axes combine`() {
        val shown = EventLogFilter.apply(
            sample(),
            EventLogQuery(text = "a", levels = setOf(LogLevel.Info), categories = setOf("Playback")),
        )

        assertTrue(shown.all { it.level == LogLevel.Info && it.tag == "Playback" && "a" in it.line.lowercase() })
        assertEquals(1, shown.size)
    }

    @Test
    fun `a query that matches nothing returns nothing rather than everything`() {
        assertEquals(emptyList(), EventLogFilter.apply(sample(), EventLogQuery(text = "zzzz")))
    }

    // ------------------------------------------------------------------ what the chips are built from

    /**
     * Categories come from the buffer, in first-appearance order.
     *
     * From the buffer because a chip for a category nothing has logged always yields an empty list. In
     * appearance order because alphabetical would re-sort the row as new lines arrive, moving a chip out
     * from under a finger.
     */
    @Test
    fun `categories are those present, in the order they appear`() {
        assertEquals(listOf("Playback", "Download", "Sync"), EventLogFilter.categoriesIn(sample()))
    }

    /**
     * Levels are the opposite choice on purpose: **severity order**, not appearance order.
     *
     * Severity has an order a reader already knows, and `Error` sitting before `Info` because it happened
     * first would read as a mistake. The sample logs them out of order so this asserts the sort rather than
     * agreeing with the input by accident.
     */
    @Test
    fun `levels are those present, in severity order`() {
        assertEquals(
            listOf(LogLevel.Debug, LogLevel.Info, LogLevel.Warn, LogLevel.Error),
            EventLogFilter.levelsIn(sample()),
        )
    }

    @Test
    fun `an empty buffer offers no chips at all`() {
        assertEquals(emptyList(), EventLogFilter.categoriesIn(emptyList()))
        assertEquals(emptyList(), EventLogFilter.levelsIn(emptyList()))
    }

    // ------------------------------------------------------------------ saying so

    /**
     * [EventLogQuery.isNarrowed] is what makes the sheet report "showing 12 of 400" and offer a reset. A
     * bare count over a filtered list is the half-truth that sends somebody hunting for a line that is
     * there — so each axis has to set it, including a search of nothing but spaces, which narrows nothing.
     */
    @Test
    fun `isNarrowed is true for each axis and false for blank text`() {
        assertTrue(EventLogQuery(text = "x").isNarrowed)
        assertTrue(EventLogQuery(levels = setOf(LogLevel.Warn)).isNarrowed)
        assertTrue(EventLogQuery(categories = setOf("Sync")).isNarrowed)
        assertFalse(EventLogQuery(text = "   ").isNarrowed)
    }

    /** Order is the caller's business: the sheet reverses for display, so the filter must not reorder. */
    @Test
    fun `the filter preserves order`() {
        val events = sample()

        assertEquals(events.map { it.line }, EventLogFilter.apply(events, EventLogQuery()).map { it.line })
    }

    private fun sample(): List<LoggedEvent> = listOf(
        event(LogLevel.Info, "Playback", "A book was opened"),
        event(LogLevel.Debug, "Download", "A file finished"),
        event(LogLevel.Warn, "Playback", "A controller was refused"),
        event(LogLevel.Error, "Sync", "The server rejected a position"),
    )

    private var tick = 0L

    private fun event(level: LogLevel, tag: String, line: String) =
        LoggedEvent(at = Instant.ofEpochMilli(tick++), level = level, tag = tag, line = line)
}
