package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.domain.book
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SeriesOrderTest {
    @Test
    fun `adding a gap preserves all existing sequence strings and a matching book fills it`() {
        val original = order("1", "2", "3", "5")
        val placed = original.addMissing(4)
        assertEquals(listOf(1, 2, 3, 4, 5), placed.slots.map { it.number })
        assertEquals(setOf(4), placed.missingBooks)
        assertTrue(placed.changes.isEmpty())
        assertEquals(placed, placed.addMissing(3))

        val refreshed = SeriesOrder.from(shelf("1", "2", "3", "4", "5"), placed.missingBooks)
        assertTrue(refreshed.missingBooks.isEmpty())
        assertTrue(refreshed.changes.isEmpty())
    }

    @Test
    fun `inline insertion shifts contiguous books only and fills a natural gap without edits`() {
        val original = order("1", "2", "3", "5")
        val inserted = original.insertMissing(2)
        assertEquals(setOf(2), inserted.missingBooks)
        assertEquals(mapOf("b1" to "3", "b2" to "4"), inserted.changes.toWireValues())
        assertTrue(original.insertMissing(4).changes.isEmpty())
        assertEquals(original, inserted.restoreLayout(original))
        val full = order("999999")
        assertEquals(full, full.insertMissing(999999))
    }

    @Test
    fun `dissolving a group preserves part order and all following books`() {
        val group = order("4.01 (Part 1)", "4.02 (Part 2)", "4.03 (Part 3)", "5", "9")
        val split = group.ungroup(group.slots.first().key)
        assertEquals(listOf(4, 5, 6, 7, 9), split.slots.map { it.number })
        assertEquals(listOf("b0", "b1", "b2", "b3", "b4"), split.slots.map { it.content.books.single().value })
        assertEquals(group, split.restoreLayout(group))
    }

    @Test
    fun `undo after a partial save keeps acknowledged and uncertain writes and restores original spelling`() {
        val original = order("01", "2")
        val moved = original.move(row(original, 1), row(original, 0), SeriesOrderMove.Reorder)
        val partial = moved.recordSave(moved.changes.take(1), moved.changes.last())
        val undo = partial.restoreLayout(original)
        assertEquals(mapOf("b0" to "01", "b1" to "2"), undo.changes.toWireValues())
        assertEquals("1", undo.changes.single { it.bookId.value == "b1" }.before)
        assertEquals(setOf("2"), undo.changes.single { it.bookId.value == "b0" }.possibleBefore)
    }

    @Test
    fun `moving a book vertically changes the affected positions and leaves later numbers alone`() {
        val original = order("1", "2", "3", "5")
        val moved = original.move(row(original, 2), row(original, 0), SeriesOrderMove.Reorder)
        assertEquals(mapOf("b2" to "1", "b0" to "2", "b1" to "3"), moved.changes.toWireValues())
        assertEquals(listOf(1, 2, 3, 5), moved.slots.map { it.number })
        val reversed = moved.move(row(moved, 0), row(moved, 2), SeriesOrderMove.Reorder)
        assertTrue(reversed.changes.isEmpty())
    }

    @Test
    fun `reordering unnumbered books assigns the unchanged prefix needed to preserve the order`() {
        val original = order("", "", "", "")
        val moved = original.move(row(original, 2), row(original, 1), SeriesOrderMove.Reorder)
        assertEquals(mapOf("b0" to "1", "b2" to "2", "b1" to "3"), moved.changes.toWireValues())

        val partial = moved.recordSave(moved.changes.take(1), moved.changes[1])
        assertEquals(mapOf("b2" to "2", "b1" to "3"), partial.changes.toWireValues())
        val undo = partial.restoreLayout(original)
        assertEquals(mapOf("b0" to "", "b2" to ""), undo.changes.toWireValues())
        assertEquals("1", undo.changes.first().before)
        assertEquals(setOf("2"), undo.changes.last().possibleBefore)
    }

    @Test
    fun `parts can be joined, reordered vertically and lifted back out`() {
        val original = order("1", "2", "3", "4", "5", "6", "8")
        val first = original.move(row(original, 4), row(original, 3), SeriesOrderMove.Indent)
        val group = first.move(row(first, 4), row(first, 3), SeriesOrderMove.Indent)
        assertEquals(
            mapOf("b3" to "4.01 (Part 1)", "b4" to "4.02 (Part 2)", "b5" to "4.03 (Part 3)"),
            group.changes.toWireValues(),
        )
        val reordered = group.move(
            row(group, 3, "b5"),
            row(group, 3, "b3"),
            SeriesOrderMove.Reorder,
        )
        assertEquals(
            mapOf("b5" to "4.01 (Part 1)", "b3" to "4.02 (Part 2)", "b4" to "4.03 (Part 3)"),
            reordered.changes.toWireValues(),
        )
        val moved = row(reordered, 3, "b4")
        val separate = reordered.move(moved, moved, SeriesOrderMove.Outdent)
        assertTrue(separate.changes.none { it.bookId == LibraryItemId("b4") })
        assertEquals(SeriesSlotContent.Book(LibraryItemId("b4")), separate.slots.single { it.number == 5 }.content)
        assertEquals(listOf(1, 2, 3, 4, 5, 8), separate.slots.map { it.number })
    }

    @Test
    fun `only marked parts are grouped and adding placeholders leaves independent decimal positions alone`() {
        val order = order("4.01 (Part 1)", "4.02 (Part 2)", "4.5", "6", "Prequel")
        assertIs<SeriesSlotContent.Parts>(order.slots[0].content)
        assertIs<SeriesSlotContent.Book>(order.slots[1].content)
        assertTrue(order.addMissing(3).changes.isEmpty())
    }

    private fun order(vararg sequence: String) = SeriesOrder.from(shelf(*sequence), emptySet())

    private fun shelf(vararg sequence: String): SeriesShelf = groupIntoSeries(
        sequence.mapIndexed { index, value -> book(id = "b$index", sequence = value) },
    ).single()

    private fun row(order: SeriesOrder, index: Int, part: String? = null) =
        SeriesOrderRow(order.slots[index].key, part?.let(::LibraryItemId))

    private fun List<SeriesSequenceChange>.toWireValues() = associate { it.bookId.value to it.after }
}
