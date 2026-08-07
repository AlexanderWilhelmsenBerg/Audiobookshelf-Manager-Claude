package com.example.shelfplayer.feature.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
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
internal fun BookCover(book: Book, modifier: Modifier = Modifier, aspect: Float = SQUARE) {
    val url = LocalCoverUrls.current.forBook(book)
    Box(
        modifier = modifier
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(8.dp))
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
 * A small cover for a list row, where the text beside it sets the height.
 *
 * Fixed rather than proportional: a row whose height depends on its title wrapping is a list that
 * jitters as it scrolls.
 */
@Composable
internal fun BookCoverThumbnail(book: Book, modifier: Modifier = Modifier) {
    BookCover(book = book, modifier = modifier.size(THUMBNAIL))
}

/**
 * Audiobook covers are square far more often than they are portrait — the artwork is derived from
 * album art conventions rather than from book jackets. A square box crops a portrait cover less badly
 * than a 2:3 box letterboxes a square one.
 */
private const val SQUARE = 1f

private val THUMBNAIL = 56.dp

/** Kept beside [BookCover] so a card and a row cannot disagree about the gap after the image. */
internal val CoverGap = 12.dp

internal fun Modifier.coverPadding() = padding(end = CoverGap)
