package com.example.shelfplayer.feature.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.a11y.assertEveryControlIsBigEnough
import com.example.shelfplayer.a11y.assertEveryControlIsLabelled
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.model.library.Series
import com.example.shelfplayer.domain.library.BookGroup
import com.example.shelfplayer.domain.library.BookGroupKind
import com.example.shelfplayer.domain.library.SeriesShelf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.time.Duration

/** LIB-002 / LIB-003 — cover-led collection cards remain usable on the smallest supported layout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp", fontScale = 2.0f)
class CollectionCardsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `series author and genre artwork cards survive a narrow screen and large type`() {
        val books = (1..4).map(::book)
        compose.setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SeriesCard(
                        shelf = SeriesShelf(
                            series = Series(ServerId("server"), SeriesId("series"), "The Long Voyage"),
                            books = books.take(3),
                        ),
                        onClick = {},
                    )
                    GroupCard(
                        group = BookGroup(
                            kind = BookGroupKind.Author,
                            key = "author-one",
                            label = "Ada Longname",
                            books = books.take(3),
                        ),
                        onClick = {},
                    )
                    GroupCard(
                        group = BookGroup(
                            kind = BookGroupKind.Genre,
                            key = "science fiction and fantasy",
                            label = "Science Fiction & Fantasy",
                            books = books,
                        ),
                        onClick = {},
                    )
                }
            }
        }

        listOf("The Long Voyage", "Ada Longname", "Science Fiction & Fantasy").forEach { label ->
            compose.onNodeWithText(label).assertExists()
        }
        compose.assertEveryControlIsLabelled()
        compose.assertEveryControlIsBigEnough()
    }

    private fun book(index: Int) = Book(
        serverId = ServerId("server"),
        libraryId = LibraryId("library"),
        id = LibraryItemId("book-$index"),
        title = "Book $index with a long title",
        subtitle = null,
        description = null,
        authors = emptyList(),
        narrators = emptyList(),
        seriesMemberships = emptyList(),
        genres = emptyList(),
        tags = emptyList(),
        publisher = null,
        publishedYear = null,
        language = null,
        isbn = null,
        asin = null,
        duration = Duration.ZERO,
        trackCount = 1,
        sizeBytes = 0,
        coverPath = null,
        addedAt = null,
        remoteUpdatedAt = null,
        lastFetchedAt = Instant.EPOCH,
        isExplicit = false,
        isAbridged = false,
        progress = null,
        localAvailability = LocalAvailability.NotDownloaded,
    )
}
