package com.example.shelfplayer.feature.series

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.domain.library.SeriesOrder
import com.example.shelfplayer.domain.library.SeriesOrderMove
import com.example.shelfplayer.domain.library.SeriesOrderRow
import com.example.shelfplayer.domain.library.SeriesShelf
import com.example.shelfplayer.domain.library.SeriesSlotContent
import com.example.shelfplayer.ui.glass.playerChromeClearance
import kotlin.math.abs

@Composable
internal fun SeriesEditor(
    shelf: SeriesShelf,
    editor: SeriesEditorState,
    actions: SeriesEditorActions,
    modifier: Modifier = Modifier,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    val slots = editor.order.slots
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.series_editor_instructions),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        editor.result?.let { result ->
            val detail = result.error?.summary.orEmpty()
            Text(
                text = if (result.applied > 0) {
                    pluralStringResource(
                        R.plurals.series_editor_partial_save,
                        result.total,
                        result.applied,
                        result.total,
                        detail,
                    )
                } else {
                    detail
                },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = { adding = true }, enabled = !editor.saving) {
            Text(stringResource(R.string.series_editor_add_missing))
        }
        val rows = remember(editor.order) { editor.order.toEditorRows() }
        val titles = remember(shelf.books) { shelf.books.associate { it.id to it.title } }
        SeriesEditorList(rows, titles, editor.saving, actions, Modifier.weight(1f))
    }
    if (adding) {
        AddMissingDialog(
            occupied = slots.mapTo(mutableSetOf()) { it.number },
            onDismiss = { adding = false },
            onAdd = {
                actions.addMissing(it)
                adding = false
            },
        )
    }
}

@Composable
internal fun SeriesMissingCard(number: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.series_editor_book_number, number),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.series_editor_missing), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AddMissingDialog(occupied: Set<Int>, onDismiss: () -> Unit, onAdd: (Int) -> Unit) {
    val suggestion = remember(occupied) { (1..SeriesOrder.MAX_BOOK_NUMBER).firstOrNull { it !in occupied } }
    var text by rememberSaveable { mutableStateOf(suggestion?.toString().orEmpty()) }
    val number = text.toIntOrNull()?.takeIf { it in 1..SeriesOrder.MAX_BOOK_NUMBER && it !in occupied }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.series_editor_add_missing)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.series_editor_position)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = text.isNotBlank() && number == null,
                supportingText = {
                    if (text.isNotBlank() && number == null) Text(stringResource(R.string.series_editor_invalid_slot))
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { number?.let(onAdd) }, enabled = number != null) {
                Text(stringResource(R.string.series_editor_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.series_editor_cancel)) } },
    )
}

@Composable
private fun SeriesEditorList(
    rows: List<EditorRow>,
    titles: Map<LibraryItemId, String>,
    saving: Boolean,
    actions: SeriesEditorActions,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    val rowsByKey = remember(rows) { rows.associateBy { it.key } }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val drag = remember(list, rowsByKey) { SeriesDragState(list, rowsByKey) }
    val target = drag.target
    ScrollWhileDragging(list, drag)
    LazyColumn(
        state = list,
        modifier = modifier.fillMaxWidth().seriesReorderGesture(drag, saving, rtl, actions.move),
        contentPadding = PaddingValues(bottom = 16.dp + playerChromeClearance()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(rows, key = EditorRow::key) { row ->
            SeriesEditorItem(row, rows, titles, saving, target == row, actions, drag)
        }
    }
}

@Composable
private fun ScrollWhileDragging(list: LazyListState, drag: SeriesDragState) {
    val scrollMargin = with(LocalDensity.current) { SCROLL_MARGIN.toPx() }
    val direction = drag.y?.let { y ->
        when {
            y < list.layoutInfo.viewportStartOffset + scrollMargin -> -1
            y > list.layoutInfo.viewportEndOffset - scrollMargin -> 1
            else -> 0
        }
    } ?: 0
    LaunchedEffect(direction, list) {
        if (direction == 0) return@LaunchedEffect
        while (list.scrollBy(direction * SCROLL_STEP_PX) != 0f) {
            withFrameNanos { }
        }
    }
}

private fun Modifier.seriesReorderGesture(
    state: SeriesDragState,
    saving: Boolean,
    rtl: Boolean,
    onMove: (SeriesOrderRow, SeriesOrderRow, SeriesOrderMove) -> Unit,
): Modifier = pointerInput(state, saving, rtl) {
    if (saving) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val fromStart = if (rtl) size.width - down.position.x else down.position.x
        val row = state.handleAt(down.position.y, fromStart, PART_INDENT.toPx(), HANDLE_WIDTH.toPx())
            ?: return@awaitEachGesture
        val held = awaitLongPressOrCancellation(down.id)
        if (held == null) {
            currentEvent.changes.firstOrNull { it.id == down.id && it.changedToUp() }?.let { up ->
                up.consume()
                state.openMenu(row)
            }
            return@awaitEachGesture
        }
        held.consume()
        state.beginDrag(row, held.position.y)
        try {
            if (trackSeriesDrag(held.id, state, rtl)) state.finish(SIDEWAYS_DISTANCE.toPx(), onMove)
        } finally {
            state.cancel()
        }
    }
}

private suspend fun AwaitPointerEventScope.trackSeriesDrag(
    id: PointerId,
    state: SeriesDragState,
    rtl: Boolean,
): Boolean {
    while (true) {
        // The list can scroll normally until the hold is recognized. The active drag then owns moves.
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == id } ?: return false
        if (change.changedToUpIgnoreConsumed()) {
            change.consume()
            return true
        }
        if (!change.pressed) return false
        state.move(change.position - change.previousPosition, rtl)
        change.consume()
    }
}

@Composable
private fun SeriesEditorItem(
    row: EditorRow,
    rows: List<EditorRow>,
    titles: Map<LibraryItemId, String>,
    saving: Boolean,
    targeted: Boolean,
    actions: SeriesEditorActions,
    drag: SeriesDragState,
) {
    val position = when {
        row.partNumber != null -> stringResource(R.string.series_editor_part_number, row.partNumber)
        else -> stringResource(R.string.series_editor_book_number, row.number)
    }
    val title = when {
        row.missing -> stringResource(R.string.series_editor_missing)
        row.book != null -> titles[row.book].orEmpty()
        else -> stringResource(R.string.series_editor_parts)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = if (row.partNumber == null) 0.dp else PART_INDENT),
        color = if (targeted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Row(
            modifier = Modifier.padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorItemMenu(row, rows, title, !saving, actions, drag)
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(position, style = MaterialTheme.typography.labelLarge)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun EditorItemMenu(
    row: EditorRow,
    rows: List<EditorRow>,
    title: String,
    enabled: Boolean,
    actions: SeriesEditorActions,
    drag: SeriesDragState,
) {
    val siblings = if (row.partNumber == null) {
        rows.filter { it.partNumber == null }
    } else {
        rows.filter { it.position.slotKey == row.position.slotKey && it.partNumber != null }
    }
    val index = siblings.indexOf(row)
    val previousParent = rows.lastOrNull {
        it.partNumber == null && it.number < row.number && !it.missing
    }
    ReorderHandle(stringResource(R.string.series_editor_move_label, title), enabled) { drag.openMenu(row) }
    DropdownMenu(expanded = drag.menuKey == row.key, onDismissRequest = drag::closeMenu) {
        val move = { target: EditorRow, kind: SeriesOrderMove ->
            drag.closeMenu()
            actions.move(row.position, target.position, kind)
        }
        siblings.getOrNull(index - 1)?.let { adjacent ->
            EditorMenuAction(stringResource(R.string.series_editor_up)) { move(adjacent, SeriesOrderMove.Reorder) }
        }
        siblings.getOrNull(index + 1)?.let { adjacent ->
            EditorMenuAction(stringResource(R.string.series_editor_down)) { move(adjacent, SeriesOrderMove.Reorder) }
        }
        if (row.book != null && previousParent != null) {
            EditorMenuAction(stringResource(R.string.series_editor_indent)) {
                move(previousParent, SeriesOrderMove.Indent)
            }
        }
        if (row.partNumber != null) {
            EditorMenuAction(stringResource(R.string.series_editor_outdent)) { move(row, SeriesOrderMove.Outdent) }
        }
        if (row.missing) {
            EditorMenuAction(stringResource(R.string.series_editor_remove_missing)) {
                drag.closeMenu()
                actions.removeMissing(row.number)
            }
        }
    }
}

@Composable
private fun ReorderHandle(label: String, enabled: Boolean, open: () -> Unit) {
    Box(
        modifier = Modifier.size(HANDLE_WIDTH)
            .onKeyEvent { event ->
                val activate = event.key == Key.Enter || event.key == Key.Spacebar
                if (enabled && activate && event.type == KeyEventType.KeyUp) {
                    open()
                    true
                } else {
                    false
                }
            }
            .focusable(enabled)
            .semantics {
                contentDescription = label
                role = Role.Button
                if (enabled) {
                    onClick {
                        open()
                        true
                    }
                } else {
                    disabled()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.DragHandle, contentDescription = null)
    }
}

@Composable
private fun EditorMenuAction(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

private data class EditorRow(
    val position: SeriesOrderRow,
    val number: Int,
    val book: LibraryItemId? = null,
    val partNumber: Int? = null,
    val missing: Boolean = false,
) {
    val key: String get() = position.part?.let { "part:${it.value}" } ?: position.slotKey
}

private data class SeriesDrag(val source: SeriesOrderRow, val y: Float, val x: Float = 0f)

private class SeriesDragState(private val list: LazyListState, private val rows: Map<String, EditorRow>) {
    private var active: SeriesDrag? by mutableStateOf(null)
    var menuKey: String? by mutableStateOf(null)
        private set
    val y: Float? get() = active?.y
    val target: EditorRow? get() = active?.let { list.targetAt(it.y, rows) }

    fun handleAt(y: Float, fromStart: Float, indent: Float, handleWidth: Float): EditorRow? {
        val row = list.rowAt(y, rows) ?: return null
        val handleStart = if (row.partNumber == null) 0f else indent
        return row.takeIf { fromStart in handleStart..(handleStart + handleWidth) }
    }

    fun beginDrag(row: EditorRow, y: Float) {
        closeMenu()
        active = SeriesDrag(row.position, y)
    }

    fun openMenu(row: EditorRow) {
        menuKey = row.key
    }

    fun closeMenu() {
        menuKey = null
    }

    fun move(amount: Offset, rtl: Boolean) {
        val direction = if (rtl) -amount.x else amount.x
        active = active?.let { it.copy(y = it.y + amount.y, x = it.x + direction) }
    }

    fun cancel() {
        active = null
    }

    fun finish(shift: Float, onMove: (SeriesOrderRow, SeriesOrderRow, SeriesOrderMove) -> Unit) {
        val end = active ?: return
        val destination = target?.position ?: end.source
        active = null
        val move = when {
            end.x > shift -> SeriesOrderMove.Indent
            end.x < -shift -> SeriesOrderMove.Outdent
            else -> SeriesOrderMove.Reorder
        }
        onMove(end.source, destination, move)
    }
}

private fun SeriesOrder.toEditorRows(): List<EditorRow> = buildList {
    for (slot in slots) {
        val position = SeriesOrderRow(slot.key)
        when (val content = slot.content) {
            is SeriesSlotContent.Book -> add(EditorRow(position, slot.number, book = content.id))

            is SeriesSlotContent.Parts -> {
                add(EditorRow(position, slot.number))
                content.books.forEachIndexed { index, id ->
                    add(EditorRow(position.copy(part = id), slot.number, book = id, partNumber = index + 1))
                }
            }

            SeriesSlotContent.Missing -> add(EditorRow(position, slot.number, missing = true))
        }
    }
}

private fun LazyListState.rowAt(y: Float, rows: Map<String, EditorRow>): EditorRow? =
    layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.let { rows[it.key] }

private fun LazyListState.targetAt(y: Float, rows: Map<String, EditorRow>): EditorRow? =
    layoutInfo.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - y) }?.let { rows[it.key] }

private val HANDLE_WIDTH = 48.dp
private val PART_INDENT = 24.dp
private val SIDEWAYS_DISTANCE = 28.dp
private val SCROLL_MARGIN = 56.dp
private const val SCROLL_STEP_PX = 8f
