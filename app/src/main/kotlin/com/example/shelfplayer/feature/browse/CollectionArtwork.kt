package com.example.shelfplayer.feature.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.SubcomposeAsyncImage
import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.library.Book

/**
 * LIB-002 / LIB-003 — cached book artwork gives browse groups a visual identity while offline.
 *
 * Authors carry only a synchronized portrait-present flag and timestamp—not the server's private image
 * path—so their verified image route is resolved from the stored remote id at render time. An unconfirmed,
 * missing, loading or offline portrait becomes an explicit fan of that author's cached book covers.
 * Genres use a mosaic and series use their ordered covers as a stack, keeping all three axes visually
 * distinct.
 */
@Composable
internal fun CollectionArtwork(
    books: List<Book>,
    style: CollectionArtworkStyle,
    modifier: Modifier = Modifier,
    authorId: AuthorId? = null,
) {
    Box(
        modifier = modifier
            .clip(ArtworkShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        when (style) {
            CollectionArtworkStyle.Series -> SeriesCoverStack(books)
            CollectionArtworkStyle.Author -> AuthorArtwork(books, authorId)
            CollectionArtworkStyle.Genre -> GenreCoverMosaic(books)
        }
        style.badgeIcon()?.let { icon ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(BADGE_SIZE),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                tonalElevation = 4.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(BADGE_ICON_SIZE))
                }
            }
        }
    }
}

/**
 * Tries the real author portrait first; cached book covers are the complete loading/error/offline state.
 * The image loader is application-provided and uses the authenticated OkHttp client, like [BookCover].
 */
@Composable
private fun BoxScope.AuthorArtwork(books: List<Book>, authorId: AuthorId?) {
    val author = authorId?.let { id ->
        books.asSequence().flatMap { book -> book.authors.asSequence() }.firstOrNull { it.id == id }
    }
    val portraitUrl = author?.let(LocalAuthorUrls.current::forAuthor)
    if (portraitUrl == null) {
        AuthorCoverFan(books)
        return
    }
    SubcomposeAsyncImage(
        model = portraitUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
        loading = { AuthorCoverFallback(books) },
        error = { AuthorCoverFallback(books) },
    )
}

@Composable
private fun AuthorCoverFallback(books: List<Book>) {
    Box(modifier = Modifier.fillMaxSize()) { AuthorCoverFan(books) }
}

@Composable
private fun BoxScope.SeriesCoverStack(books: List<Book>) {
    books.take(MAX_STACKED_COVERS).forEachIndexed { index, book ->
        BookCover(
            book = book,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (10 + (index * 18)).dp, y = (8 - (index * 4)).dp)
                .size(STACK_COVER_SIZE)
                .shadow(4.dp, SmallCoverShape)
                .zIndex((MAX_STACKED_COVERS - index).toFloat()),
            shape = SmallCoverShape,
        )
    }
}

@Composable
private fun BoxScope.AuthorCoverFan(books: List<Book>) {
    val rotations = listOf(-8f, 0f, 8f)
    books.take(MAX_STACKED_COVERS).forEachIndexed { index, book ->
        BookCover(
            book = book,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (8 + (index * 22)).dp, y = if (index == 1) 8.dp else 16.dp)
                .size(FAN_COVER_SIZE)
                .graphicsLayer { rotationZ = rotations[index] }
                .shadow(4.dp, SmallCoverShape)
                .zIndex(index.toFloat()),
            shape = SmallCoverShape,
        )
    }
}

@Composable
private fun BoxScope.GenreCoverMosaic(books: List<Book>) {
    val distinct = books.distinctBy { it.id }.take(MAX_MOSAIC_COVERS)
    if (distinct.size == 1) {
        BookCover(
            book = distinct.single(),
            modifier = Modifier
                .align(Alignment.Center)
                .size(SINGLE_MOSAIC_COVER_SIZE),
            shape = SmallCoverShape,
        )
        return
    }
    distinct.forEachIndexed { index, book ->
        BookCover(
            book = book,
            modifier = Modifier
                .offset(
                    x = if (index % MOSAIC_COLUMNS == 0) MOSAIC_EDGE else MOSAIC_SECOND_COLUMN,
                    y = if (index < MOSAIC_COLUMNS) MOSAIC_EDGE else MOSAIC_SECOND_ROW,
                )
                .size(MOSAIC_COVER_SIZE),
            shape = MosaicCoverShape,
        )
    }
}

private fun CollectionArtworkStyle.badgeIcon(): ImageVector? = when (this) {
    CollectionArtworkStyle.Series -> null
    CollectionArtworkStyle.Author -> Icons.Filled.Person
    CollectionArtworkStyle.Genre -> Icons.Filled.Category
}

private const val MAX_STACKED_COVERS = 3
private const val MAX_MOSAIC_COVERS = 4
private const val MOSAIC_COLUMNS = 2
private val ArtworkShape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
private val SmallCoverShape = RoundedCornerShape(6.dp)
private val MosaicCoverShape = RoundedCornerShape(4.dp)
private val STACK_COVER_SIZE = 92.dp
private val FAN_COVER_SIZE = 82.dp
private val SINGLE_MOSAIC_COVER_SIZE = 108.dp
private val MOSAIC_COVER_SIZE = 54.dp
private val MOSAIC_EDGE = 6.dp
private val MOSAIC_SECOND_COLUMN = 64.dp
private val MOSAIC_SECOND_ROW = 64.dp
private val BADGE_SIZE = 36.dp
private val BADGE_ICON_SIZE = 20.dp
