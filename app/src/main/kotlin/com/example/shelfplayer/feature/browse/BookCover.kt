package com.example.shelfplayer.feature.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.shelfplayer.core.model.library.Book

/**
 * PRODUCT_SPEC LIB-004 — a book's cover, or a placeholder that occupies the same space.
 *
 * The placeholder is not a nicety. A shelf where some cards have an image and some collapse to text
 * is a shelf that reflows as it loads, and a `LazyRow` doing that under a finger is worse than no
 * covers at all. So the box is always drawn at [aspect], whether or not there is anything to put in it.
 *
 * `contentDescription` is null throughout: the title is already beside the cover on every card that
 * uses this, and a screen reader announcing "Cover of The Salt Harbour. The Salt Harbour." reads the
 * book twice. PRODUCT_SPEC 21 wants the information available once, not twice.
 */
@Composable
internal fun BookCover(book: Book, modifier: Modifier = Modifier, aspect: Float = SQUARE, shape: Shape = CoverShape) {
    val url = LocalCoverUrls.current.forBook(book)
    Box(
        modifier = modifier
            .aspectRatio(aspect)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * A cover for a list row, sized to the full height of the row beside it.
 *
 * The caller has to give the row `Modifier.height(IntrinsicSize.Min)`, which is what lets
 * `fillMaxHeight` mean "as tall as the text column" rather than "as tall as the screen". The square
 * then follows from the height, so a row with a two-line title gets a larger cover than one with a
 * single line and neither has to be told a number.
 *
 * Square-cornered, unlike [BookCover]'s default: this one sits flush against the left edge of a card
 * that is already rounding its own corners, and a rounded rectangle inside a rounded rectangle reads
 * as a mistake.
 */
@Composable
internal fun BookCoverThumbnail(book: Book, modifier: Modifier = Modifier) {
    BookCover(book = book, modifier = modifier.fillMaxHeight(), shape = RectangleShape)
}

/**
 * Audiobook covers are square far more often than they are portrait — the artwork is derived from
 * album art conventions rather than from book jackets. A square box crops a portrait cover less badly
 * than a 2:3 box letterboxes a square one.
 */
private const val SQUARE = 1f

/** The rounding a free-standing cover gets. A cover flush inside a card passes `RectangleShape`. */
private val CoverShape = RoundedCornerShape(8.dp)

/** Kept beside [BookCover] so a card and a row cannot disagree about the gap after the image. */
internal val CoverGap = 12.dp

internal fun Modifier.coverPadding() = padding(end = CoverGap)
