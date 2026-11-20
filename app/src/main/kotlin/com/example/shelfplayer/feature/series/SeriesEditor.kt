package com.example.shelfplayer.feature.series

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.library.SeriesOrder
import com.example.shelfplayer.domain.library.SeriesOrderMove
import com.example.shelfplayer.domain.library.SeriesOrderRow
import com.example.shelfplayer.domain.library.SeriesShelf
import com.example.shelfplayer.domain.library.SeriesSlotContent
import com.example.shelfplayer.feature.browse.BookCover
import com.example.shelfplayer.ui.glass.playerChromeClearance
import kotlin.math.roundToInt

@Composable
internal fun SeriesEditor(
    shelf: SeriesShelf,
    editor: SeriesEditorState,
    actions: SeriesEditorActions,
    modifier: Modifier = Modifier,
) {
    val rows = remember(editor.order.slots) { editor.order.toEditorRows() }
    val books = remember(shelf.books) { shelf.books.associateBy(Book::id) }
    val list = rememberLazyListState()
    val drag = remember(list, rows) { SeriesDragState(list, rows) }
    val move by rememberUpdatedState(actions.move)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val groupTravel = with(LocalDensity.current) { GROUP_TRAVEL.toPx() }
    val drop by remember(drag, groupTravel) { derivedStateOf { drag.drop(groupTravel) } }
    val enabled = !editor.saving
    ScrollWhileDragging(list, drag)
    Box(modifier.fillMaxSize().padding(horizontal = 16.dp).clipToBounds()) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().seriesReorderGesture(drag, enabled, rtl) { from, to, kind ->
                move(from, to, kind)
            },
            contentPadding = PaddingValues(bottom = 16.dp + playerChromeClearance()),
        ) {
            item(key = "instructions") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(shelf.series.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.series_editor_instructions),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    editor.result?.let { result ->
                        val detail = result.error?.summary.orEmpty()
                        val message = if (result.applied > 0) {
                            pluralStringResource(
                                R.plurals.series_editor_partial_save,
                                result.total,
                                result.applied,
                                result.total,
                                detail,
                            )
                        } else {
                            detail
                        }
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            var nextNumber = 1
            rows.forEach { row ->
                if (row.partNumber == null) {
                    val number = nextNumber
                    val key = beforeKey(row)
                    item(key = key) {
                        InsertBoundary(number, drop?.boundary == key, enabled, actions.insertMissing)
                    }
                    nextNumber = row.number + 1
                }
                item(key = row.key) {
                    EditorCard(
                        row,
                        rows,
                        books,
                        enabled,
                        drop,
                        lifted = drag.source == row,
                        actions = actions,
                    )
                }
            }
            val finalNumber = nextNumber
            item(key = END_BOUNDARY) {
                InsertBoundary(finalNumber, drop?.boundary == END_BOUNDARY, enabled, actions.insertMissing)
            }
        }
        drag.source?.let { row ->
            var previewHeight by remember { mutableIntStateOf(0) }
            Surface(
                modifier = Modifier.offset {
                    IntOffset(
                        drag.horizontalTravel.roundToInt().coerceIn(-24.dp.roundToPx(), 24.dp.roundToPx()),
                        drag.previewTop(previewHeight, 8.dp.roundToPx(), drop),
                    )
                }.onSizeChanged { previewHeight = it.height }.clearAndSetSemantics { },
                shape = MaterialTheme.shapes.small,
                shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(
                    Modifier.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    books[row.book]?.let { BookCover(it, Modifier.size(32.dp)) }
                    Text(
                        editorRowTitle(row, books),
                        Modifier.padding(end = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun InsertBoundary(number: Int, targeted: Boolean, enabled: Boolean, insert: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(if (targeted) 64.dp else 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val line = if (targeted) colors.primary else colors.outlineVariant
        HorizontalDivider(Modifier.weight(1f), color = line)
        if (targeted) {
            Text(
                stringResource(R.string.series_editor_drop_here),
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
            )
        } else {
            IconButton(onClick = { insert(number) }, enabled = enabled && number <= SeriesOrder.MAX_BOOK_NUMBER) {
                Icon(
                    Icons.Filled.Add,
                    stringResource(R.string.series_editor_insert_at, number),
                    tint = colors.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(Modifier.weight(1f), color = line)
    }
}

@Composable
private fun EditorCard(
    row: EditorRow,
    rows: List<EditorRow>,
    books: Map<LibraryItemId, Book>,
    enabled: Boolean,
    drop: SeriesDrop?,
    lifted: Boolean,
    actions: SeriesEditorActions,
) {
    val colors = MaterialTheme.colorScheme
    val cue = drop?.takeIf { it.row == row }?.move
    val title = editorRowTitle(row, books)
    Column(Modifier.groupFrame(row)) {
        if (row.partNumber != null && drop?.boundary == beforeKey(row)) PartDropLine()
        EditorCardSurface(row, cue, lifted) {
            Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Box { EditorItemMenu(row, rows, books, title, enabled, actions) }
                books[row.book]?.let { BookCover(it, Modifier.padding(end = 8.dp).size(48.dp)) }
                Column(Modifier.weight(1f).padding(vertical = 8.dp, horizontal = 4.dp)) {
                    Text(
                        editorPosition(row, cue),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
                if (row.isGroup) {
                    IconButton(
                        onClick = { actions.ungroup(row.position.slotKey) },
                        enabled = enabled,
                        modifier = Modifier.align(Alignment.Top),
                    ) {
                        Icon(Icons.Filled.Close, stringResource(R.string.series_editor_ungroup, row.number))
                    }
                }
            }
        }
        if (row.lastPart && drop?.boundary == endKey(row)) PartDropLine()
    }
}

@Composable
private fun EditorCardSurface(row: EditorRow, cue: SeriesOrderMove?, lifted: Boolean, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val grouped = row.isGroup || row.partNumber != null
    val isTarget = cue == SeriesOrderMove.Indent || cue == SeriesOrderMove.Outdent
    Surface(
        modifier = Modifier.fillMaxWidth().alpha(if (lifted) 0.5f else 1f),
        color = when {
            isTarget -> colors.primaryContainer
            row.isGroup -> colors.surfaceContainerHigh
            else -> colors.surfaceContainer
        },
        border = when {
            isTarget -> BorderStroke(1.dp, colors.primary)
            grouped -> null
            else -> BorderStroke(1.dp, colors.outlineVariant)
        },
        content = content,
    )
}

// Each lazy row draws its segment of the same frame, including groups larger than a viewport.
@Composable
private fun Modifier.groupFrame(row: EditorRow): Modifier {
    if (!row.isGroup && row.partNumber == null) return this
    val colors = MaterialTheme.colorScheme
    val line = colors.outline
    return background(colors.surfaceContainerHigh).drawBehind {
        val stroke = 1.dp.toPx()
        val left = stroke / 2
        val right = size.width - left
        drawLine(line, Offset(left, 0f), Offset(left, size.height), stroke)
        drawLine(line, Offset(right, 0f), Offset(right, size.height), stroke)
        if (row.isGroup) drawLine(line, Offset(0f, left), Offset(size.width, left), stroke)
        val bottom = size.height - left
        if (row.lastPart) drawLine(line, Offset(0f, bottom), Offset(size.width, bottom), stroke)
    }.padding(
        start = if (row.isGroup) 1.dp else PART_INDENT,
        end = if (row.isGroup) 1.dp else 8.dp,
        top = 1.dp,
        bottom = if (row.lastPart) 8.dp else 0.dp,
    )
}

@Composable
private fun PartDropLine() {
    HorizontalDivider(Modifier.padding(vertical = 6.dp), thickness = 3.dp, color = MaterialTheme.colorScheme.primary)
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
private fun EditorItemMenu(
    row: EditorRow,
    rows: List<EditorRow>,
    books: Map<LibraryItemId, Book>,
    title: String,
    enabled: Boolean,
    actions: SeriesEditorActions,
) {
    var menu by remember { mutableStateOf(false) }
    var choosingGroup by remember { mutableStateOf(false) }
    IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.size(HANDLE_WIDTH)) {
        Icon(Icons.Filled.DragIndicator, contentDescription = stringResource(R.string.series_editor_move_label, title))
    }
    if (!menu && !choosingGroup) return
    val siblings = if (row.partNumber == null) {
        rows.filter { it.partNumber == null }
    } else {
        rows.filter { it.position.slotKey == row.position.slotKey && it.partNumber != null }
    }
    val index = siblings.indexOf(row)
    val groupTargets = remember(row, rows) {
        if (row.book == null) {
            emptyList()
        } else {
            rows.filter { it.position.slotKey != row.position.slotKey && !it.missing }
        }
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        val move = { target: EditorRow, kind: SeriesOrderMove ->
            menu = false
            actions.move(row.position, target.position, kind)
        }
        siblings.getOrNull(index - 1)?.let { adjacent ->
            EditorMenuAction(stringResource(R.string.series_editor_up)) { move(adjacent, SeriesOrderMove.Reorder) }
        }
        siblings.getOrNull(index + 1)?.let { adjacent ->
            EditorMenuAction(stringResource(R.string.series_editor_down)) { move(adjacent, SeriesOrderMove.Reorder) }
        }
        if (groupTargets.isNotEmpty()) {
            EditorMenuAction(stringResource(R.string.series_editor_indent)) {
                menu = false
                choosingGroup = true
            }
        }
        if (row.partNumber != null) {
            EditorMenuAction(stringResource(R.string.series_editor_outdent)) { move(row, SeriesOrderMove.Outdent) }
        }
        if (row.missing) {
            EditorMenuAction(stringResource(R.string.series_editor_remove_missing)) {
                menu = false
                actions.removeMissing(row.number)
            }
        }
    }
    if (choosingGroup) {
        SeriesGroupTargetDialog(
            targets = groupTargets,
            books = books,
            onDismiss = { choosingGroup = false },
            onSelect = { target ->
                choosingGroup = false
                actions.move(row.position, target.position, SeriesOrderMove.Indent)
            },
        )
    }
}

@Composable
private fun SeriesGroupTargetDialog(
    targets: List<EditorRow>,
    books: Map<LibraryItemId, Book>,
    onDismiss: () -> Unit,
    onSelect: (EditorRow) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.series_editor_group_target)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(targets, key = { it.key }) { target ->
                    val position = target.partNumber?.let { part ->
                        stringResource(R.string.series_editor_book_and_part, target.number, part)
                    } ?: stringResource(R.string.series_editor_book_number, target.number)
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(editorRowTitle(target, books))
                                Text(position, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = { onSelect(target) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.series_editor_cancel)) } },
    )
}

@Composable
private fun editorPosition(row: EditorRow, cue: SeriesOrderMove?): String = when {
    cue == SeriesOrderMove.Indent -> stringResource(R.string.series_editor_drop_group)
    cue == SeriesOrderMove.Outdent -> stringResource(R.string.series_editor_outdent)
    row.partNumber != null -> stringResource(R.string.series_editor_part_number, row.partNumber)
    else -> stringResource(R.string.series_editor_book_number, row.number)
}

@Composable
private fun editorRowTitle(row: EditorRow, books: Map<LibraryItemId, Book>): String = when {
    row.missing -> stringResource(R.string.series_editor_missing)
    row.book != null -> books[row.book]?.title.orEmpty()
    else -> stringResource(R.string.series_editor_parts)
}

@Composable
private fun EditorMenuAction(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

internal data class EditorRow(
    val position: SeriesOrderRow,
    val number: Int,
    val book: LibraryItemId? = null,
    val partNumber: Int? = null,
    val missing: Boolean = false,
    val lastPart: Boolean = false,
) {
    val isGroup: Boolean get() = book == null && !missing
    val key: String get() = position.part?.let { "part:${it.value}" } ?: position.slotKey
}

private fun SeriesOrder.toEditorRows(): List<EditorRow> = buildList {
    for (slot in slots) {
        val position = SeriesOrderRow(slot.key)
        when (val content = slot.content) {
            is SeriesSlotContent.Book -> add(EditorRow(position, slot.number, book = content.id))

            is SeriesSlotContent.Parts -> {
                add(EditorRow(position, slot.number))
                content.books.forEachIndexed { index, id ->
                    add(
                        EditorRow(
                            position.copy(part = id),
                            slot.number,
                            book = id,
                            partNumber = index + 1,
                            lastPart = index == content.books.lastIndex,
                        ),
                    )
                }
            }

            SeriesSlotContent.Missing -> add(EditorRow(position, slot.number, missing = true))
        }
    }
}
