package com.example.shelfplayer.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.library.HomeShelves
import com.example.shelfplayer.domain.library.SeriesProgress
import com.example.shelfplayer.feature.browse.BookCover
import com.example.shelfplayer.ui.glass.GlassCard

/**
 * PRODUCT_SPEC LIB-002 — the three horizontal shelves the app opens on.
 *
 * A shelf is a *preview*, capped in the domain at twenty. The axis destinations in the bottom bar are
 * where an exhaustive list lives, and a row that scrolls for a minute is neither a preview nor a list.
 *
 * Each shelf is omitted entirely when it is empty rather than rendered with a placeholder. A new
 * account has nothing in progress and no finished series, and three headings over three blank strips
 * is a worse first screen than one heading over the books that do exist.
 */
internal fun LazyListScope.homeShelves(
    shelves: HomeShelves,
    onBookSelected: (LibraryItemId) -> Unit,
    onBookPlaySelected: (LibraryItemId) -> Unit,
    onSeriesSelected: (SeriesId) -> Unit,
) {
    if (shelves.continueListening.isNotEmpty()) {
        item(key = "shelf-continue") {
            BookShelfRow(
                titleRes = R.string.shelf_continue_listening,
                books = shelves.continueListening,
                onBookSelected = onBookSelected,
                onBookPlaySelected = onBookPlaySelected,
            )
        }
    }
    if (shelves.continueSeries.isNotEmpty()) {
        item(key = "shelf-series") {
            SeriesShelfRow(entries = shelves.continueSeries, onSeriesSelected = onSeriesSelected)
        }
    }
    if (shelves.recentlyAdded.isNotEmpty()) {
        item(key = "shelf-recent") {
            BookShelfRow(
                titleRes = R.string.shelf_recently_added,
                books = shelves.recentlyAdded,
                onBookSelected = onBookSelected,
                onBookPlaySelected = onBookPlaySelected,
            )
        }
    }
    // Last on purpose. The first three answer "what was I doing"; these two answer "what now", which is
    // the question a user only reaches once the first three have failed to interest them — except on a
    // brand-new account, where the first three are absent and these are the whole screen.
    if (shelves.listenAgain.isNotEmpty()) {
        item(key = "shelf-again") {
            BookShelfRow(
                titleRes = R.string.shelf_listen_again,
                books = shelves.listenAgain,
                onBookSelected = onBookSelected,
                onBookPlaySelected = onBookPlaySelected,
            )
        }
    }
    if (shelves.discover.isNotEmpty()) {
        item(key = "shelf-discover") {
            BookShelfRow(
                titleRes = R.string.shelf_discover,
                books = shelves.discover,
                onBookSelected = onBookSelected,
                onBookPlaySelected = onBookPlaySelected,
            )
        }
    }
}

@Composable
private fun BookShelfRow(
    titleRes: Int,
    books: List<Book>,
    onBookSelected: (LibraryItemId) -> Unit,
    onBookPlaySelected: (LibraryItemId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShelfHeading(text = stringResource(titleRes))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = books, key = { it.id.value }) { book ->
                ShelfCard(
                    title = book.title,
                    subtitle = book.authors.firstOrNull()?.name,
                    progress = book.progress?.fractionComplete?.takeIf { book.progress?.isFinished == false },
                    onClick = { onBookSelected(book.id) },
                    playLabel = stringResource(
                        if (book.progress != null && book.progress?.isFinished == false) {
                            R.string.shelf_resume_book
                        } else {
                            R.string.shelf_play_book
                        },
                        book.title,
                    ),
                    onPlay = { onBookPlaySelected(book.id) },
                    cover = {
                        BookCover(
                            book = book,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RectangleShape,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SeriesShelfRow(
    entries: List<SeriesProgress>,
    onSeriesSelected: (SeriesId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShelfHeading(text = stringResource(R.string.shelf_continue_series))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = entries, key = { it.series.id.value }) { entry ->
                ShelfCard(
                    title = entry.series.name,
                    subtitle = stringResource(
                        R.string.shelf_series_position,
                        entry.currentBookNumber,
                        entry.bookCount,
                    ),
                    detail = entry.nextBook.title,
                    progress = entry.finishedCount.toFloat() / entry.bookCount.toFloat(),
                    titleLines = 1,
                    onClick = { onSeriesSelected(entry.series.id) },
                    // The next book's cover stands for the series. A series has no artwork of its own,
                    // and the book the user would actually start is the most useful thing to show.
                    cover = {
                        BookCover(
                            book = entry.nextBook,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RectangleShape,
                        )
                    },
                )
            }
        }
    }
}

/**
 * A fixed-width card, because a `LazyRow` of intrinsically sized cards jumps as it scrolls: each new
 * item measures its own title and the row's rhythm changes under the finger.
 *
 * The cover is a slot rather than a `Book`, because the series shelf shows the *next book's* cover for
 * a row whose subject is a series.
 */
@Composable
private fun ShelfCard(
    title: String,
    subtitle: String?,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    titleLines: Int = TITLE_LINES,
    playLabel: String? = null,
    onPlay: (() -> Unit)? = null,
    cover: @Composable () -> Unit = {},
) {
    GlassCard(
        onClick = onClick,
        modifier = modifier.width(SHELF_CARD_WIDTH),
        shape = RoundedCornerShape(SHELF_CARD_CORNER_RADIUS),
    ) {
        Column {
            ShelfCover(
                progress = progress,
                playLabel = playLabel,
                onPlay = onPlay,
                cover = cover,
            )
            ShelfCardLabels(
                title = title,
                subtitle = subtitle,
                detail = detail,
                titleLines = titleLines,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ShelfCover(
    progress: Float?,
    playLabel: String?,
    onPlay: (() -> Unit)?,
    cover: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        cover()
        if (onPlay != null && playLabel != null) {
            FilledIconButton(
                onClick = onPlay,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(PLAY_BUTTON_SIZE),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = playLabel,
                    modifier = Modifier.size(PLAY_ICON_SIZE),
                )
            }
        }
        ShelfProgress(
            progress = progress,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

@Composable
private fun ShelfCardLabels(
    title: String,
    subtitle: String?,
    detail: String?,
    titleLines: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // PRODUCT_SPEC 4 — every card in a row is the same height.
        //
        // Regular book titles reserve two lines so neighbouring cards stay aligned. Series titles use one
        // line because their second and third rows are reserved for "Book X of Y" and the next book title.
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            minLines = titleLines,
            maxLines = titleLines,
            overflow = TextOverflow.Ellipsis,
        )
        // Always drawn, blank when unknown. An absent author must cost the same height as a present one,
        // or a book with no author sits a line higher than its neighbours.
        Text(
            text = subtitle.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ShelfProgress(progress: Float?, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress ?: 0f },
        modifier = modifier.fillMaxWidth(),
        color = if (progress == null) Color.Transparent else ProgressIndicatorDefaults.linearColor,
        trackColor = if (progress == null) Color.Transparent else ProgressIndicatorDefaults.linearTrackColor,
    )
}

@Composable
private fun ShelfHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}

/** PRODUCT_SPEC 21 — sized in `dp` so it grows with the display density but not with the font scale. */
private val SHELF_CARD_WIDTH = 160.dp

/** Slightly squarer than the Material card default while keeping the cover's top corners visibly softened. */
private val SHELF_CARD_CORNER_RADIUS = 8.dp

/** PLAY-001 / PRODUCT_SPEC 21 — a full touch target even when the visible circle reads as an overlay. */
/**
 * The play control on a shelf card.
 *
 * Halved from 48.dp on request — at 48 it covered close to a third of a 160.dp card and read as the card's
 * subject rather than as an action on it.
 *
 * **40.dp is a floor, not a preference.** `AccessibilityAssertions.MINIMUM_VISUAL_TARGET` fails any
 * interactive node below it, and that assertion measures the node's own layout size rather than the
 * platform's expanded touch bounds. Going to a literal 24.dp would fail the accessibility net and, more to
 * the point, would be a genuinely small target on a card people tap one-handed while walking.
 *
 * So the *visible* shrink comes from the icon rather than the button: the filled circle stays at the
 * minimum a finger needs, and the glyph inside it halves, which is the change actually being asked for.
 * Giving the button a 24.dp body and a hidden 48.dp target was the other option and was rejected — the
 * invisible overhang would sit over the cover, and the device run has already found one defect where a
 * card's own click area swallowed a nested action.
 */
private val PLAY_BUTTON_SIZE = 40.dp

/** Half of the original 48.dp control, which is the size the button now reads as. */
private val PLAY_ICON_SIZE = 24.dp

/** Two lines, always, for regular book titles. */
private const val TITLE_LINES = 2
