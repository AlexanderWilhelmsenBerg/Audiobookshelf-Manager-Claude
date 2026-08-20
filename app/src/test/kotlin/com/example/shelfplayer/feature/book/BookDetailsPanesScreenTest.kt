package com.example.shelfplayer.feature.book

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.playback.PlaybackUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * PRODUCT_SPEC 4 / §129 — the two panes really are side by side, asserted as geometry.
 *
 * ### Why position and not visibility
 *
 * The first version of this test asked whether the synopsis was on screen without scrolling, on the
 * theory that a phone would push it below the fold and a tablet would not. It failed, and it was right to:
 * in a Robolectric render the cover has no bitmap and therefore no intrinsic height, so the phone layout
 * is short enough to fit everything. The premise was an assumption about content height, which is not
 * what the layout guarantees.
 *
 * What the layout does guarantee is *where* the synopsis is. In one column it starts at the left margin;
 * beside a [BookScreen] action pane it starts past that pane's width. That holds at any content height,
 * with or without a cover, which is what makes it worth asserting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookDetailsPanesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** A phone stacks: the synopsis heading sits at the left margin, under everything else. */
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a phone puts the synopsis in the same column as the actions`() {
        render()

        assertTrue(aboutHeadingLeftEdge() < ONE_COLUMN_MARGIN, "the synopsis was not at the left margin")
    }

    /** A tablet in landscape puts it in the second pane, past the action pane's width. */
    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `a tablet puts the synopsis beside the actions`() {
        render()

        assertTrue(aboutHeadingLeftEdge() > SECOND_PANE_EDGE, "the synopsis was not in a second pane")
    }

    /**
     * A 700dp window — a foldable open — keeps one column.
     *
     * Asserted because it is the decision somebody would most plausibly reverse, and reversing it would
     * make both panes too narrow to read rather than making anything better.
     */
    @Test
    @Config(qualifiers = "w700dp-h1000dp")
    fun `a medium window keeps one column`() {
        render()

        assertTrue(aboutHeadingLeftEdge() < ONE_COLUMN_MARGIN, "a 700dp window split into two panes")
    }

    /** Where the synopsis heading starts, in dp from the left edge of the window. */
    private fun aboutHeadingLeftEdge(): Dp = composeRule.onNodeWithText(ABOUT).getUnclippedBoundsInRoot().left

    private fun render() {
        composeRule.setContent {
            BookScreen(
                uiState = BookUiState.Loaded(book()),
                playback = PlaybackUiState(
                    bookId = null,
                    title = "",
                    author = null,
                    artworkUri = null,
                    isPlaying = false,
                    isLoading = false,
                    position = Duration.ZERO,
                    duration = Duration.ZERO,
                ),
                menu = BookMenuState(webUrl = null, canDownload = false, download = DownloadButtonState.NotDownloaded),
                actions = BookActions(
                    onPlay = {},
                    onTogglePlayPause = {},
                    onFinishedChanged = {},
                    onDiscardProgress = {},
                    onOpenWebClient = {},
                    onDownloadClicked = {},
                    onRemoveDownload = {},
                    onManageDownloads = {},
                ),
                onNavigateUp = {},
            )
        }
    }

    private fun book() = Book(
        serverId = ServerId("server-1"),
        id = LibraryItemId("book-1"),
        libraryId = LibraryId("lib-fiction"),
        title = "The Salt Harbour",
        subtitle = null,
        authors = emptyList(),
        narrators = emptyList(),
        seriesMemberships = emptyList(),
        duration = 11.hours,
        // Long enough that a phone cannot fit it and the header on one screen, which is what makes the
        // difference between the two layouts observable at all.
        description = LONG_BLURB,
        genres = emptyList(),
        tags = emptyList(),
        publishedYear = 2019,
        publisher = "Northgate",
        language = "English",
        isbn = "9780000000001",
        asin = null,
        isExplicit = false,
        isAbridged = false,
        coverPath = null,
        trackCount = 12,
        sizeBytes = 1_000,
        remoteUpdatedAt = null,
        addedAt = null,
        lastFetchedAt = Instant.ofEpochMilli(0),
        progress = null,
        localAvailability = LocalAvailability.NotDownloaded,
    )

    private companion object {
        val LONG_BLURB = "A harbour town, a missing keeper, and a winter nobody was ready for. ".repeat(8)

        /**
         * The synopsis heading, which is the *only* thing these tests assert on.
         *
         * The book's title would have been the obvious second anchor and is the wrong one: it appears
         * twice — in the top bar and in the header — so it is displayed at every width regardless of the
         * layout, and asserting it would have passed for a reason unrelated to panes.
         */
        const val ABOUT = "About this book"

        /**
         * Comfortably inside a one-column layout's left margin, which is 16dp of section padding.
         *
         * A threshold rather than an equality, because the exact inset belongs to `Section` and is free to
         * change; what this test owns is *which pane* the heading is in.
         */
        val ONE_COLUMN_MARGIN = 64.dp

        /** Past the action pane, whose width is 360dp. Anything beyond it is the second pane. */
        val SECOND_PANE_EDGE = 360.dp
    }
}
