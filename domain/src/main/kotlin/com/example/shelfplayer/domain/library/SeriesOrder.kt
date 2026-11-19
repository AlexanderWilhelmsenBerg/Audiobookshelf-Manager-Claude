package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.SeriesSequence

/** A draft for one series, with explicit gaps and one level of multipart books. */
@ConsistentCopyVisibility
data class SeriesOrder private constructor(
    val slots: List<SeriesOrderSlot>,
    private val originals: Map<LibraryItemId, String>,
    private val edited: Set<LibraryItemId> = emptySet(),
) {
    val missingBooks: Set<Int>
        get() = slots.filter { it.content == SeriesSlotContent.Missing }.mapTo(mutableSetOf()) { it.number }

    val changes: List<SeriesSequenceChange>
        get() = assignments(slots).mapNotNull { (id, position) ->
            val before = originals.getValue(id)
            val assigned = position.sequence()
            if (id in edited && before != assigned) SeriesSequenceChange(id, before, assigned) else null
        }

    fun addMissing(number: Int): SeriesOrder {
        if (number !in 1..MAX_BOOK_NUMBER || slots.any { it.number == number }) return this
        return copy(slots = (slots + SeriesOrderSlot(number, SeriesSlotContent.Missing)).sortedBy { it.number })
    }

    fun removeMissing(number: Int): SeriesOrder =
        copy(slots = slots.filterNot { it.number == number && it.content == SeriesSlotContent.Missing })

    fun move(from: SeriesOrderRow, to: SeriesOrderRow, move: SeriesOrderMove): SeriesOrder {
        val source = slots.indexOfFirst { it.key == from.slotKey }
        val target = slots.indexOfFirst { it.key == to.slotKey }
        if (source < 0 || target < 0) return this
        if (from == to && move != SeriesOrderMove.Outdent) return this
        return when (move) {
            SeriesOrderMove.Reorder -> reorder(source, target, from.part, to.part)
            SeriesOrderMove.Indent -> indent(source, target, from.part, to.part)
            SeriesOrderMove.Outdent -> from.part?.let { outdent(source, it) } ?: this
        }
    }

    private fun reorder(source: Int, target: Int, part: LibraryItemId?, targetPart: LibraryItemId?): SeriesOrder {
        if (part != null) {
            return if (source == target && targetPart != null) reorderParts(source, part, targetPart) else this
        }
        if (source == target) return this
        val contents = slots.map { it.content }.toMutableList()
        contents.add(target, contents.removeAt(source))
        return update(slots.mapIndexed { index, slot -> slot.copy(content = contents[index]) })
    }

    private fun reorderParts(source: Int, part: LibraryItemId, targetPart: LibraryItemId): SeriesOrder {
        val ids = (slots[source].content as? SeriesSlotContent.Parts)?.books?.toMutableList() ?: return this
        val oldIndex = ids.indexOf(part)
        val newIndex = ids.indexOf(targetPart)
        if (oldIndex < 0 || newIndex < 0) return this
        ids.add(newIndex, ids.removeAt(oldIndex))
        val changed = slots.toMutableList().apply {
            this[source] = this[source].copy(content = SeriesSlotContent.Parts(ids))
        }
        return update(changed)
    }

    private fun indent(source: Int, target: Int, part: LibraryItemId?, targetPart: LibraryItemId?): SeriesOrder {
        if (source == target) return this
        val from = slots[source]
        val into = slots[target]
        val id = part ?: (from.content as? SeriesSlotContent.Book)?.id ?: return this
        if (into.content.books.isEmpty() || id !in from.content.books) return this
        val parts = into.content.books.toMutableList()
        val insertion = targetPart?.let(parts::indexOf)?.plus(1)?.takeIf { it > 0 } ?: parts.size
        parts.add(insertion, id)
        val remaining = from.without(id)
        val replacement = slots.mapNotNull {
            when (it.number) {
                from.number -> remaining
                into.number -> into.copy(content = SeriesSlotContent.Parts(parts))
                else -> it
            }
        }
        return update(replacement)
    }

    private fun outdent(source: Int, id: LibraryItemId): SeriesOrder {
        val slot = slots[source]
        if (slot.content !is SeriesSlotContent.Parts || id !in slot.content.books) return this
        val before = slots.take(source)
        val parent = slot.without(id)
        val newNumber = if (parent == null) slot.number else slot.number + 1
        val newSlot = SeriesOrderSlot(newNumber, SeriesSlotContent.Book(id))
        var last = newNumber
        val after = slots.drop(source + 1).map { later ->
            val moved = later.copy(number = later.number.coerceAtLeast(last + 1))
            last = moved.number
            moved
        }
        return update(before + listOfNotNull(parent, newSlot) + after)
    }

    private fun update(updated: List<SeriesOrderSlot>): SeriesOrder {
        val previous = assignments(slots)
        val now = assignments(updated)
        val moved = now.keys.filter { now[it] != previous[it] }
        return copy(slots = updated, edited = edited + moved)
    }

    companion object {
        const val MAX_BOOK_NUMBER = 999_999
        private val Whole = Regex("""\d+""")
        private val Part = Regex("""(\d+)\.(\d+) \(Part (\d+)\)""")

        fun from(shelf: SeriesShelf, placeholders: Set<Int>): SeriesOrder {
            val sources = shelf.books.map { book ->
                book.id to book.seriesMemberships.first { it.series.id == shelf.series.id }.sequence
            }
            val groups = sources.filter { partNumber(it.second.raw) != null }
                .groupBy { partNumber(it.second.raw) }
            val emittedGroups = mutableSetOf<Int>()
            val occupied = sources.mapNotNull { wholeNumber(it.second.raw) ?: partNumber(it.second.raw) }.toSet()
            val entries = sources.mapNotNull { (id, sequence) ->
                val group = partNumber(sequence.raw)
                when {
                    group == null -> Candidate(sequence, wholeNumber(sequence.raw), SeriesSlotContent.Book(id))

                    emittedGroups.add(group) -> Candidate(
                        sequence,
                        group,
                        SeriesSlotContent.Parts(groups.getValue(group).map { it.first }),
                    )

                    else -> null
                }
            }
            val gaps = placeholders.filter { it in 1..MAX_BOOK_NUMBER && it !in occupied }.map {
                Candidate(SeriesSequence.parse(it.toString()), it, SeriesSlotContent.Missing)
            }
            var previous = 0
            val slots = (entries + gaps).sortedBy { it.sequence }.map { entry ->
                val number = (entry.number ?: previous + 1).coerceAtLeast(previous + 1)
                previous = number
                SeriesOrderSlot(number, entry.content)
            }
            return SeriesOrder(slots, sources.associate { (id, sequence) -> id to sequence.raw })
        }

        fun occupiedNumber(sequence: SeriesSequence): Int? = wholeNumber(sequence.raw) ?: partNumber(sequence.raw)

        private fun wholeNumber(raw: String): Int? = raw.takeIf(Whole::matches)?.toBookNumber()

        private fun partNumber(raw: String): Int? {
            val match = Part.matchEntire(raw) ?: return null
            val (root, decimal, part) = match.destructured
            val ordinal = part.toIntOrNull()?.takeIf { it > 0 }
            return root.toBookNumber()?.takeIf { ordinal != null && decimal.toIntOrNull() == ordinal }
        }

        private fun String.toBookNumber(): Int? = toIntOrNull()?.takeIf { it in 1..MAX_BOOK_NUMBER }
    }
}

data class SeriesOrderSlot(val number: Int, val content: SeriesSlotContent) {
    val key: String
        get() = when (content) {
            is SeriesSlotContent.Book -> "book:${content.id.value}"
            is SeriesSlotContent.Parts -> "group:${content.books.first().value}"
            SeriesSlotContent.Missing -> "gap:$number"
        }

    internal fun without(id: LibraryItemId): SeriesOrderSlot? {
        val remaining = content.books - id
        return when (remaining.size) {
            0 -> null
            1 -> copy(content = SeriesSlotContent.Book(remaining.single()))
            else -> copy(content = SeriesSlotContent.Parts(remaining))
        }
    }
}

sealed interface SeriesSlotContent {
    val books: List<LibraryItemId>

    data class Book(val id: LibraryItemId) : SeriesSlotContent {
        override val books: List<LibraryItemId> get() = listOf(id)
    }

    data class Parts(override val books: List<LibraryItemId>) : SeriesSlotContent

    data object Missing : SeriesSlotContent {
        override val books: List<LibraryItemId> = emptyList()
    }
}

data class SeriesOrderRow(val slotKey: String, val part: LibraryItemId? = null)

enum class SeriesOrderMove { Reorder, Indent, Outdent }

data class SeriesSequenceChange(val bookId: LibraryItemId, val before: String, val after: String)

private data class Candidate(val sequence: SeriesSequence, val number: Int?, val content: SeriesSlotContent)

private data class SequencePosition(val number: Int, val part: Int? = null, val width: Int = 0) {
    fun sequence(): String {
        if (part == null) return number.toString()
        val decimal = part.toString().padStart(width, '0')
        return "$number.$decimal (Part $part)"
    }
}

private const val MIN_PART_WIDTH = 2

private fun assignments(slots: List<SeriesOrderSlot>): Map<LibraryItemId, SequencePosition> = buildMap {
    for ((number, content) in slots) {
        when (content) {
            is SeriesSlotContent.Book -> put(content.id, SequencePosition(number))

            is SeriesSlotContent.Parts -> {
                val width = content.books.size.toString().length.coerceAtLeast(MIN_PART_WIDTH)
                content.books.forEachIndexed { index, id -> put(id, SequencePosition(number, index + 1, width)) }
            }

            SeriesSlotContent.Missing -> Unit
        }
    }
}
