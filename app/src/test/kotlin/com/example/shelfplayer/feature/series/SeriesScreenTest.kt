package com.example.shelfplayer.feature.series

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.shelfplayer.a11y.assertEveryControlIsBigEnough
import com.example.shelfplayer.a11y.assertEveryControlIsLabelled
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.model.library.MediaProgress
import com.example.shelfplayer.core.model.library.Series
import com.example.shelfplayer.core.model.library.SeriesMembership
import com.example.shelfplayer.domain.library.SeriesShelf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

/**
 * PRODUCT_SPEC LIB-003 — the series screen answers *where am I* and *carry on* without opening a book.
 *
 * The gap this closes described the screen as *"a sparse list without a cover/summary/progress header or
 * direct per-book play/resume actions"*. These hold the two halves that were built: the header's arithmetic
 * and the two play paths. The summary half is genuinely absent — `Series` carries no description — and is
 * left recorded rather than tested into existence.
 *
 * Rendered at the smallest supported layout and a doubled font scale, like every other screen test here: a
 * header with a cover, three lines and a button is exactly the shape that has laid out four pixels tall
 * before (`docs/gaps.md` §51).
 *
 * The `ScreenTest` suffix is `app/build.gradle.kts`'s contract rather than a description: any class calling
 * `createComposeRule` must carry it, because `ui-test-manifest` is a `debugImplementation` and the release
 * unit-test variant has no activity to launch. This class was called `SeriesHeaderTest` first and failed
 * exactly as that comment predicts — an unresolvable launcher intent in `testReleaseUnitTest` only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp", fontScale = 2.0f)
class SeriesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The header counts what is finished, and the button names the book that is not.
     *
     * `nextBook` is the first *unfinished* one rather than the first *started* one, so a series read in
     * order offers book three while books one and two are done and three has never been opened.
     */
    @Test
    fun `the header counts progress and offers the first unfinished book`() {
        var played: LibraryItemId? = null
        compose.setContent {
            MaterialTheme {
                SeriesScreen(
                    uiState = SeriesUiState(isLoading = false, shelf = shelf(finishedThrough = 2)),
                    onBookSelected = {},
                    onBookPlaySelected = { played = it },
                    onNavigateUp = {},
                )
            }
        }

        compose.onNodeWithText("2 of 4 books finished").assertExists()
        compose.onNodeWithText("12h 0m in total").assertExists()

        // By its spoken name, which is unique. The visible label is the title alone, and that alone
        // matches the row for the same book — which is exactly what a screen reader would have heard.
        compose.onNodeWithContentDescription("Continue with Book 3").performClick()

        assertEquals(LibraryItemId("book-3"), played)
    }

    /**
     * A finished series offers no *carry on*, because there is nowhere to carry on to.
     *
     * Restarting a series somebody just finished is not something to leave under a thumb, so the button is
     * absent rather than repurposed.
     */
    @Test
    fun `a finished series says so and offers no continue button`() {
        compose.setContent {
            MaterialTheme {
                SeriesScreen(
                    uiState = SeriesUiState(isLoading = false, shelf = shelf(finishedThrough = 4)),
                    onBookSelected = {},
                    onBookPlaySelected = {},
                    onNavigateUp = {},
                )
            }
        }

        compose.onNodeWithText("4 of 4 books finished").assertExists()
        compose.onNodeWithText("You have finished every book in this series.").assertExists()
        // The point of the test: no continue button exists for any of the four books.
        (1..BOOK_COUNT).forEach { index ->
            compose.onNodeWithContentDescription("Continue with Book $index").assertDoesNotExist()
        }
    }

    /** LIB-003's other half: a row plays its own book rather than opening it. */
    @Test
    fun `a row's play button starts that book instead of opening it`() {
        var played: LibraryItemId? = null
        var opened: LibraryItemId? = null
        compose.setContent {
            MaterialTheme {
                SeriesScreen(
                    uiState = SeriesUiState(isLoading = false, shelf = shelf(finishedThrough = 0)),
                    onBookSelected = { opened = it },
                    onBookPlaySelected = { played = it },
                    onNavigateUp = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Play Book 2").performClick()

        assertEquals(LibraryItemId("book-2"), played)
        assertEquals(null, opened, "the play button must not also open the book")
    }

    /** PRODUCT_SPEC 21 — every new control is labelled and reachable at a doubled font scale. */
    @Test
    fun `the header and the rows stay usable at a doubled font scale`() {
        compose.setContent {
            MaterialTheme {
                SeriesScreen(
                    uiState = SeriesUiState(isLoading = false, shelf = shelf(finishedThrough = 1)),
                    onBookSelected = {},
                    onBookPlaySelected = {},
                    onNavigateUp = {},
                )
            }
        }

        compose.assertEveryControlIsLabelled()
        compose.assertEveryControlIsBigEnough()
    }

    private fun shelf(finishedThrough: Int): SeriesShelf {
        val series = Series(ServerId("server"), SeriesId("series"), "The Long Voyage")
        return SeriesShelf(
            series = series,
            books = (1..BOOK_COUNT).map { index -> book(series, index, isFinished = index <= finishedThrough) },
        )
    }

    private fun book(series: Series, index: Int, isFinished: Boolean) = Book(
        serverId = ServerId("server"),
        libraryId = LibraryId("library"),
        id = LibraryItemId("book-$index"),
        title = "Book $index",
        subtitle = null,
        description = null,
        authors = emptyList(),
        narrators = emptyList(),
        seriesMemberships = listOf(
            SeriesMembership(series = series, sequence = SeriesSequence.parse("$index"), isPrimary = true),
        ),
        genres = emptyList(),
        tags = emptyList(),
        publisher = null,
        publishedYear = null,
        language = null,
        isbn = null,
        asin = null,
        // Four books of three hours, so the header's total is a number the test can name exactly.
        duration = 3.hours,
        trackCount = 1,
        sizeBytes = 0,
        coverPath = null,
        addedAt = null,
        remoteUpdatedAt = null,
        lastFetchedAt = Instant.EPOCH,
        isExplicit = false,
        isAbridged = false,
        progress = if (isFinished) finished(LibraryItemId("book-$index")) else null,
        localAvailability = LocalAvailability.NotDownloaded,
    )

    private fun finished(bookId: LibraryItemId) = MediaProgress(
        serverId = ServerId("server"),
        profileId = ProfileId("profile"),
        bookId = bookId,
        position = 3.hours,
        duration = 3.hours,
        isFinished = true,
        updatedAt = Instant.EPOCH,
        hasUnsyncedChanges = false,
    )

    private companion object {
        const val BOOK_COUNT = 4
    }
}
