package com.example.shelfplayer.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.library.Chapter

/**
 * PRODUCT_SPEC PLAY-003 — the chapter list, and jumping to one.
 *
 * ### It opens where you are
 *
 * A forty-chapter book opened at the top means scrolling to find where you are, every time. The list
 * scrolls to the current chapter as it appears, which makes the sheet answer "where am I" as well as
 * "take me somewhere" — and those are the two reasons anyone opens it.
 *
 * ### The current chapter is marked, not just highlighted
 *
 * A speaker icon and a bolder weight, rather than colour alone. PRODUCT_SPEC 21 does not allow colour to
 * be the only carrier of a state, and a screen reader gets the icon's description where it would get
 * nothing from a tint.
 *
 * The start time is shown against each chapter. It is what makes a list of "Chapter 7" rows useful on a
 * book whose chapters are untitled, which self-hosted audiobooks very often are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterSheet(
    chapters: List<Chapter>,
    current: Chapter?,
    onSelect: (Chapter) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val currentIndex = chapters.indexOfFirst { it.index == current?.index }
    LaunchedEffect(currentIndex) {
        // Two rows above it, so the current chapter has context rather than sitting flush at the top.
        if (currentIndex > 0) listState.scrollToItem((currentIndex - CONTEXT_ROWS).coerceAtLeast(0))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.player_chapters),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (chapters.isEmpty()) {
                // PRODUCT_SPEC LIB-004 — a book with no chapter metadata is normal for a self-hosted
                // library, and saying so beats an empty sheet the user has to interpret.
                Text(
                    text = stringResource(R.string.player_chapters_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                return@Column
            }
            LazyColumn(state = listState) {
                items(chapters, key = { it.index }) { chapter ->
                    ChapterRow(
                        chapter = chapter,
                        isCurrent = chapter.index == current?.index,
                        position = chapters.indexOf(chapter) + 1,
                        onClick = { onSelect(chapter) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: Chapter,
    isCurrent: Boolean,
    position: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isCurrent) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = stringResource(R.string.player_chapter_current),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // An untitled chapter still needs a name to tap. Its number is the honest one; inventing
                // a title from the book would put the same words on forty rows.
                text = chapter.title.ifBlank { stringResource(R.string.player_chapter_number, position) },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = chapter.start.asChapterClock(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val CONTEXT_ROWS = 2
