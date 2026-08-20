package com.example.shelfplayer.a11y

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.unit.Density
import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.feature.book.BookActions
import com.example.shelfplayer.feature.book.BookMenuState
import com.example.shelfplayer.feature.book.BookScreen
import com.example.shelfplayer.feature.book.BookUiState
import com.example.shelfplayer.feature.book.DownloadButtonState
import com.example.shelfplayer.playback.PlaybackUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * PRODUCT_SPEC §51 / 2.10 — the book screen, which is where a listener spends the most decisions.
 *
 * Three properties, and the third is the one this file exists for.
 *
 * The labelling and target checks are the same net as everywhere else. The **large-text** case is
 * specific: §51 makes large text a release requirement, and a 200% font scale is where a fixed-height row
 * clips its own label. Nothing in this project had ever rendered a screen at anything but the default
 * scale, so this is the first evidence either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class BookAccessibilityScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `every control announces itself`() {
        render()

        composeRule.assertEveryControlIsLabelled()
    }

    @Test
    fun `every control is big enough to hit`() {
        render()

        composeRule.assertEveryControlIsBigEnough()
    }

    /**
     * At double the font size the screen still renders and its title is still on it.
     *
     * A weaker assertion than it looks, and deliberately so: what a large scale actually breaks is
     * *truncation*, which the semantics tree reports as the full string either way. What it can prove is
     * that nothing throws and that the content is still reachable — a real failure mode, because a
     * `Row(Modifier.height(...))` with two lines of text in it crashes a measure pass rather than clipping.
     */
    @Test
    fun `the screen survives a doubled font scale`() {
        // Through `LocalDensity` rather than a Robolectric qualifier: there is no `fontScale` qualifier —
        // the resource system has no such axis — and the composition local is what `sp` actually reads.
        renderAt(fontScale = 2f)

        // `onFirst` because the title is on screen twice — in the top bar and in the header — and either
        // one being displayed is the property under test.
        composeRule.onAllNodesWithText("The Salt Harbour", substring = true).onFirst().assertIsDisplayed()
        composeRule.assertEveryControlIsLabelled()
    }

    private fun render() = renderAt(fontScale = 1f)

    private fun renderAt(fontScale: Float) {
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = fontScale),
            ) {
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
                    menu = BookMenuState(
                        webUrl = "https://books.example/item/book-1",
                        canDownload = true,
                        download = DownloadButtonState.NotDownloaded,
                    ),
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
    }

    private fun book() = Book(
        serverId = ServerId("server-1"),
        id = LibraryItemId("book-1"),
        libraryId = LibraryId("lib-fiction"),
        title = "The Salt Harbour",
        subtitle = "A Northgate Mystery",
        authors = listOf(Author(ServerId("server-1"), AuthorId("author-1"), "A. Writer")),
        narrators = listOf("A. Reader"),
        seriesMemberships = emptyList(),
        duration = 11.hours,
        description = "A harbour town, a missing keeper, and a winter nobody was ready for.",
        genres = listOf("Mystery"),
        tags = listOf("winter"),
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
}
