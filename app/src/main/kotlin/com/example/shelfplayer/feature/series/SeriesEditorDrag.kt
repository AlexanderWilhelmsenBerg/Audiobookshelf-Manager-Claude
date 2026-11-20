package com.example.shelfplayer.feature.series

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.domain.library.SeriesOrderMove
import com.example.shelfplayer.domain.library.SeriesOrderRow
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class SeriesDrop(val row: EditorRow, val move: SeriesOrderMove, val boundary: String? = null)

internal class SeriesDragState(private val list: LazyListState, rows: List<EditorRow>) {
    private val byKey = rows.associateBy { it.key }
    private val roots = rows.filter { it.partNumber == null }
    private val parts = rows.filter { it.partNumber != null }.groupBy { it.position.slotKey }
    private val beforeRoot = roots.withIndex().associate { beforeKey(it.value) to it.index }
    var source by mutableStateOf<EditorRow?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    private var start = Offset.Zero
    val horizontalTravel get() = pointer.x - start.x

    private fun itemAt(y: Float) = list.layoutInfo.visibleItemsInfo.firstOrNull {
        y >= it.offset &&
            y < it.offset + it.size
    }

    fun rowAt(y: Float) = byKey[itemAt(y)?.key]

    fun begin(row: EditorRow, down: Offset) {
        start = down
        pointer = down
        source = row
    }

    fun move(point: Offset) {
        pointer = point
    }

    fun clear() {
        source = null
    }

    /** Keep the lifted cover and title clear of the insertion line or highlighted destination card. */
    fun previewTop(height: Int, clearance: Int, drop: SeriesDrop?): Int {
        val layout = list.layoutInfo
        val limit = (layout.viewportSize.height - height).coerceAtLeast(0)
        val finger = pointer.y.roundToInt()
        val above = (finger - height - clearance).coerceIn(0, limit)
        val seam = layout.visibleItemsInfo.firstOrNull { it.key == drop?.boundary }
        val cue = seam ?: layout.visibleItemsInfo.firstOrNull { it.key == drop?.row?.key } ?: return above
        val bottom = cue.offset + cue.size
        if (above + height <= cue.offset - clearance || above >= bottom + clearance) return above
        val below = maxOf(finger, bottom) + clearance
        return if (below <= limit) below else (cue.offset - height - clearance).coerceIn(0, limit)
    }

    fun drop(sideways: Float): SeriesDrop? {
        val from = source ?: return null
        val item = itemAt(pointer.y) ?: return null
        if (pointer.x !in 0f..list.layoutInfo.viewportSize.width.toFloat()) return null
        val over = byKey[item.key]
        return when {
            from.partNumber != null && horizontalTravel <= -sideways && over != null ->
                SeriesDrop(from, SeriesOrderMove.Outdent)

            over != null && horizontalTravel >= sideways && canGroup(from, over, item) ->
                SeriesDrop(over, SeriesOrderMove.Indent)

            from.partNumber != null -> partInsertion(from, over, item)

            else -> bookInsertion(from, over, item)
        }
    }

    private fun canGroup(from: EditorRow, over: EditorRow, item: LazyListItemInfo): Boolean {
        val fraction = (pointer.y - item.offset) / item.size
        return from.book != null && !over.missing && from.position.slotKey != over.position.slotKey &&
            fraction in GROUP_ZONE
    }

    private fun partInsertion(from: EditorRow, over: EditorRow?, item: LazyListItemInfo): SeriesDrop? {
        if (over?.partNumber == null || over.position.slotKey != from.position.slotKey) return null
        val siblings = parts.getValue(from.position.slotKey)
        return insertion(from, siblings, siblings.indexOf(over) + lowerHalf(item, from, over))
    }

    private fun bookInsertion(from: EditorRow, over: EditorRow?, item: LazyListItemInfo): SeriesDrop? {
        val boundary = when {
            item.key == END_BOUNDARY -> roots.size

            over == null -> beforeRoot[item.key] ?: return null

            else -> {
                val rootIndex = roots.indexOfFirst { it.position.slotKey == over.position.slotKey }
                rootIndex + if (over.partNumber != null) 1 else lowerHalf(item, from, over)
            }
        }
        return insertion(from, roots, boundary)
    }

    private fun lowerHalf(item: LazyListItemInfo, from: EditorRow, over: EditorRow): Int {
        val center = item.offset + item.size / 2f
        // The centre itself means "take this book's place" in either travel direction.
        val movingDown = from.number < over.number || (from.partNumber ?: 0) < (over.partNumber ?: 0)
        return if (abs(pointer.y - center) < 1f) {
            if (movingDown) 1 else 0
        } else if (pointer.y > center) {
            1
        } else {
            0
        }
    }

    private fun insertion(from: EditorRow, siblings: List<EditorRow>, boundary: Int): SeriesDrop? {
        val index = siblings.indexOf(from)
        val targetIndex = if (boundary > index) boundary - 1 else boundary
        if (targetIndex == index) return null
        val target = siblings.getOrNull(targetIndex) ?: return null
        val seam = siblings.getOrNull(boundary)?.let(::beforeKey) ?: endKey(from)
        return SeriesDrop(target, SeriesOrderMove.Reorder, seam)
    }
}

internal const val END_BOUNDARY = "boundary:end"
internal fun beforeKey(row: EditorRow) = "before:${row.key}"
internal fun endKey(row: EditorRow) = if (row.partNumber == null) END_BOUNDARY else "end:${row.position.slotKey}"
internal val PART_INDENT = 12.dp
internal val HANDLE_WIDTH = 48.dp
internal val GROUP_TRAVEL = 72.dp
private val GROUP_ZONE = 0.25f..0.75f

@Composable
internal fun ScrollWhileDragging(list: LazyListState, drag: SeriesDragState) {
    val margin = with(LocalDensity.current) { 48.dp.toPx() }
    val step = with(LocalDensity.current) { 8.dp.toPx() }
    val direction by remember(list, drag, margin) {
        derivedStateOf {
            when {
                drag.source == null -> 0
                drag.pointer.y < list.layoutInfo.viewportStartOffset + margin -> -1
                drag.pointer.y > list.layoutInfo.viewportEndOffset - margin -> 1
                else -> 0
            }
        }
    }
    LaunchedEffect(direction, list, drag) {
        if (direction == 0) return@LaunchedEffect
        while (list.scrollBy(direction * step) != 0f) withFrameNanos { }
    }
}

internal fun Modifier.seriesReorderGesture(
    state: SeriesDragState,
    enabled: Boolean,
    rtl: Boolean,
    onMove: (SeriesOrderRow, SeriesOrderRow, SeriesOrderMove) -> Unit,
): Modifier = pointerInput(state, enabled, rtl) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val row = state.rowAt(down.position.y) ?: return@awaitEachGesture
        val fromStart = if (rtl) size.width - down.position.x else down.position.x
        if (row.isGroup && fromStart >= size.width - HANDLE_WIDTH.toPx()) return@awaitEachGesture
        val handleStart = if (row.partNumber != null) PART_INDENT.toPx() else 0f
        val pickup = if (fromStart in handleStart..(handleStart + HANDLE_WIDTH.toPx())) {
            awaitHandleDrag(down.id, down.position)
        } else {
            awaitLongPressOrCancellation(down.id)
        } ?: return@awaitEachGesture
        pickup.consume()
        state.begin(row, down.position)
        state.move(pickup.position)
        try {
            if (trackDrag(down.id, state)) {
                state.drop(GROUP_TRAVEL.toPx())?.let { onMove(row.position, it.row.position, it.move) }
            }
        } finally {
            state.clear()
        }
    }
}

private suspend fun AwaitPointerEventScope.trackDrag(id: PointerId, state: SeriesDragState): Boolean {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.singleOrNull() ?: return false
        if (change.id != id || change.isConsumed) return false
        state.move(change.position)
        val released = change.changedToUpIgnoreConsumed()
        change.consume()
        if (!change.pressed) return released
    }
}

// Take the first movement beyond touch slop before the LazyColumn starts scrolling.
// A tap passes through to the handle's ordinary accessible menu button.
private suspend fun AwaitPointerEventScope.awaitHandleDrag(id: PointerId, down: Offset): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.singleOrNull { it.id == id } ?: return null
        if (!change.pressed || change.isConsumed || event.changes.size != 1) return null
        if ((change.position - down).getDistance() > viewConfiguration.touchSlop) return change
    }
}
