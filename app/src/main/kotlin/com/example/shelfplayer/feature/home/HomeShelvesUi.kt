package com.example.shelfplayer.feature.home

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.MediaProgress
import com.example.shelfplayer.domain.library.HomeShelves
import com.example.shelfplayer.domain.library.SeriesProgress
import com.example.shelfplayer.feature.browse.BookCover
import com.example.shelfplayer.feature.browse.readable
import com.example.shelfplayer.ui.glass.GlassCard
import kotlin.math.roundToInt
import kotlin.time.Duration

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
            // PRODUCT_SPEC 4 — every card in a *row* is the same height, so whether the readings row is
            // drawn is a property of the shelf rather than of each book. A shelf where nothing has been
            // started reserves nothing; one with a single part-listened book reserves the strip on every
            // card, so its neighbours do not sit a line higher than it.
            val anyInProgress = books.any { it.listeningProgress() != null }
            items(items = books, key = { it.id.value }) { book ->
                ShelfCard(
                    progress = book.listeningProgress()?.fractionComplete,
                    onClick = { onBookSelected(book.id) },
                    playLabel = stringResource(
                        if (book.listeningProgress() != null) {
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
                    labels = { BookShelfLabels(book = book, reserveReadings = anyInProgress) },
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
                    progress = entry.finishedCount.toFloat() / entry.bookCount.toFloat(),
                    onClick = { onSeriesSelected(entry.series.id) },
                    labels = {
                        // Unchanged, deliberately. A series card's three lines are a name, a position and
                        // the next book — none of which is the book-card triple below, and the owner asked
                        // for this shelf to stay as it is.
                        SeriesShelfLabels(
                            title = entry.series.name,
                            subtitle = stringResource(
                                R.string.shelf_series_position,
                                entry.currentBookNumber,
                                entry.bookCount,
                            ),
                            detail = entry.nextBook.title,
                        )
                    },
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
    progress: Float?,
    onClick: () -> Unit,
    labels: @Composable () -> Unit,
    modifier: Modifier = Modifier,
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
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = { labels() },
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

/**
 * PRODUCT_SPEC LIB-002 / LIB-004 — a book card: where it is, who wrote it, what it is, what it is part of.
 *
 * ### The order, and why the author is first
 *
 * The owner asked for it, and the shelf is the reason it works. A shelf is browsed by *cover* — the
 * picture is what a listener recognises a book by — so the text beneath is not how they find the book, it
 * is how they place it. The author does that in one word where a title takes three, and on a shelf of
 * one writer it is the line that stops repeating and lets the titles do the distinguishing.
 *
 * ### Every line is one line, and scrolls if it does not fit
 *
 * The title used to reserve two lines so neighbouring cards stayed aligned, which cost every card a blank
 * line and still cut long titles off. Three single lines are aligned by construction, and a line too long
 * for the card scrolls its own text instead of ending in an ellipsis — the same answer, and the same
 * reasoning, as the mini player's title: it is the card's job to say what the book is, and half a title
 * does not. `Clip` rather than `Ellipsis` because an ellipsis truncates the string *before* the marquee
 * can scroll it, which produces a scrolling ellipsis and no more text than before.
 *
 * Text that fits does not move. The marquee animates only when the content is wider than its box.
 */
@Composable
private fun BookShelfLabels(book: Book, reserveReadings: Boolean) {
    if (reserveReadings) {
        BookShelfReadings(progress = book.listeningProgress())
    }
    // All three drawn whether or not there is anything to say, so a book with no author or no series is
    // the same height as one with both. A blank line costs what a filled one does.
    ShelfLine(text = book.authors.firstOrNull()?.name.orEmpty(), style = MaterialTheme.typography.bodySmall)
    ShelfLine(text = book.title, style = MaterialTheme.typography.titleSmall, muted = false)
    ShelfLine(
        // The primary membership where the server names one. A book can be third in one series and first
        // in another, and `BookCard` makes the same choice for the same reason.
        text = book.shelfSeriesName().orEmpty(),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * PRODUCT_SPEC LIB-004 — how far in, as three readings across the card.
 *
 * Elapsed, percentage, remaining — left, middle, right, which is the order the owner asked for and also
 * the order they answer in: *where am I*, *how far is that*, *how much is left*. The bar above the row
 * says the same thing without a number; this is the number, for the listener deciding whether a book
 * fits the walk home.
 *
 * ### Three plain `Text`s, and **no** `contentDescription` on the row
 *
 * The first version of this gave the row one spoken sentence and silenced its three cells, which is the
 * trick the settings dropdowns use and is wrong here. A card is a *clickable* — `GlassCard` merges its
 * whole subtree into one node — so a description set anywhere inside it becomes the description of the
 * card, and Android reads a node's description **instead of** its text. The card would have announced
 * "1h 0m in, 25 per cent, 3h 0m left" and never said which book it was. A semantics dump found it before
 * a device could.
 *
 * So the readings stay ordinary text and merge into the card's own announcement after the author, title
 * and series, which is what the card already did with its two lines. The cost, stated rather than hidden:
 * read aloud, the three are bare values whose meaning comes from their order. Fixing that properly means
 * describing the whole card in one sentence, which is a bigger change than this one and would put every
 * string on the card in two places.
 *
 * Drawn blank rather than omitted when a book has not been started, because the shelf reserves this strip
 * for every card as soon as one book on it has been (PRODUCT_SPEC 4).
 */
@Composable
private fun BookShelfReadings(progress: MediaProgress?) {
    val remaining = progress
        ?.let { (it.duration - it.position).coerceAtLeast(Duration.ZERO) }
        ?.readable()
        .orEmpty()
    val percent = progress
        ?.let { stringResource(R.string.shelf_progress_percent, (it.fractionComplete * PERCENT).roundToInt()) }
        .orEmpty()
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        ReadingText(text = progress?.position?.readable().orEmpty())
        ReadingText(text = percent)
        ReadingText(text = remaining)
    }
}

/**
 * One of the three readings, or the space that holds its line open.
 *
 * `minLines = 1` does **not** reserve a line for an empty string — a semantics dump measured a blank cell
 * at 16px against a filled one's 36px, which put a started book's title 20dp below its neighbour's on the
 * same shelf. A single space has the font's own ascent and descent, so it reserves exactly what a reading
 * would and keeps doing so at every font scale, which a fixed `height` in dp would not.
 *
 * The blank is then cleared from the semantics tree: it is spacing, and a card that merges its subtree
 * into one announcement should not read an empty string as part of the book's name.
 */
@Composable
private fun ReadingText(text: String) {
    Text(
        text = text.ifEmpty { " " },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        minLines = 1,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = if (text.isEmpty()) Modifier.clearAndSetSemantics { } else Modifier,
    )
}

/** One line of a book card: one line high, and scrolling rather than truncated when it overflows. */
@Composable
private fun ShelfLine(text: String, style: TextStyle, muted: Boolean = true) {
    Text(
        text = text,
        style = style,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
        minLines = 1,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
    )
}

/** The series shelf's three lines, unchanged: a series name, a position in it, and the next book. */
@Composable
private fun SeriesShelfLabels(title: String, subtitle: String, detail: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        minLines = 1,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        minLines = 1,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        minLines = 1,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The book's progress, or `null` when it has not been started or is already finished.
 *
 * One definition rather than the `progress != null && !isFinished` pair spelled out at each of the four
 * places that used to ask: the play button's label, the bar, whether the shelf reserves the readings
 * strip, and the readings themselves. Four copies of a two-part condition is three chances to write it
 * with the wrong half.
 */
private fun Book.listeningProgress(): MediaProgress? = progress?.takeIf { !it.isFinished }

/** PRODUCT_SPEC LIB-003 — the series a general shelf names: the primary one, else the first. */
private fun Book.shelfSeriesName(): String? =
    (seriesMemberships.firstOrNull { it.isPrimary } ?: seriesMemberships.firstOrNull())?.series?.name

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

/** A fraction as a whole number of per cent. */
private const val PERCENT = 100
