package com.example.shelfplayer.feature.book

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.model.library.MediaProgress
import com.example.shelfplayer.playback.PlaybackUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * PRODUCT_SPEC LIB-004 / PLAY-004 / 21 — the overflow menu, as a hand reaches it.
 *
 * Screen assertions rather than ViewModel ones, deliberately. PR 1 of this closeout shipped a complete,
 * well-tested bookmark feature with **no visible way to make a bookmark**, because every test asked whether a
 * bookmark could be stored and none asked whether one could be made. Every case here is "can this be found
 * and pressed", including the two that must *not* be pressable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class BookOverflowMenuScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Everything the owner asked for is in the menu, in the order they asked for it. */
    @Test
    fun `the menu offers every action`() {
        render()
        openMenu()

        listOf(
            "History",
            "Mark as finished",
            "Discard progress",
            "Manage local files",
            "Delete local item",
            "Go to web client",
            "More info",
        ).forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    /**
     * PRODUCT_SPEC DL-003 — *Manage local files* opens the storage screen. No longer a placeholder.
     *
     * Live whatever this book's state is: the screen it opens is about the **device**, and a user who
     * wonders where their space went should not have to find a downloaded book first to be allowed to ask.
     */
    @Test
    fun `manage local files opens the storage screen`() {
        var opened = 0
        render(onManageDownloads = { opened++ })
        openMenu()

        composeRule.onNodeWithText("Manage local files").assertIsEnabled().performClick()

        assertEquals(1, opened)
    }

    /**
     * PRODUCT_SPEC DL-003 — *Delete local item* is live, and only when there is something to delete.
     *
     * The owner asked for removal in the menu *and* the download button, and this is one action reached two
     * ways rather than two that could disagree: both open the same confirmation.
     */
    @Test
    fun `delete local item is live only for a downloaded book`() {
        render(download = DownloadButtonState.Downloaded)
        openMenu()

        composeRule.onNodeWithText("Delete local item").assertIsEnabled()
    }

    @Test
    fun `delete local item has nothing to delete on a book that is not downloaded`() {
        render(download = DownloadButtonState.NotDownloaded)
        openMenu()

        composeRule.onNodeWithText("Delete local item").assertIsNotEnabled()
    }

    /** The label names the state it would put the book into, so it flips on a finished book. */
    @Test
    fun `a finished book offers to un-finish it`() {
        render(book = book(progress(position = 11.hours, isFinished = true)))
        openMenu()

        composeRule.onNodeWithText("Mark as not finished").assertIsDisplayed()
        composeRule.onNodeWithText("Mark as finished").assertDoesNotExist()
    }

    /** And pressing it reports the state it named rather than the one it was in. */
    @Test
    fun `marking finished reports true`() {
        val reported = mutableListOf<Boolean>()
        render(onFinishedChanged = { reported += it })
        openMenu()

        composeRule.onNodeWithText("Mark as finished").performClick()

        assertEquals(listOf(true), reported)
    }

    /**
     * PRODUCT_SPEC 21 — discarding asks first, and the question says what it will actually do.
     *
     * The three claims in the body are the point: the position goes, the audio does not, and the server is
     * told too. "Discard progress" alone could mean deleting the download.
     */
    @Test
    fun `discarding progress asks first, and says what it does`() {
        var discarded = 0
        render(onDiscardProgress = { discarded++ })
        openMenu()

        composeRule.onNodeWithText("Discard progress").performClick()

        composeRule.onNodeWithText("Discard your progress?").assertIsDisplayed()
        composeRule.onNodeWithText(
            "This sends the book back to the beginning, on this device and on your server. No audio files " +
                "are deleted, nothing is removed from your library, and any download stays where it is. " +
                "You can start listening again from anywhere.",
        ).assertIsDisplayed()
        assertEquals(0, discarded, "nothing happens until it is confirmed")

        composeRule.onNodeWithText("Keep it").performClick()
        assertEquals(0, discarded, "and declining does nothing at all")
    }

    /** Confirming is what performs it. */
    @Test
    fun `confirming discards`() {
        var discarded = 0
        render(onDiscardProgress = { discarded++ })
        openMenu()
        composeRule.onNodeWithText("Discard progress").performClick()

        composeRule.onNodeWithText("Discard progress").performClick()

        assertEquals(1, discarded)
    }

    /**
     * A book nobody has started has nothing to discard, and the row says so by being disabled.
     *
     * A destructive-sounding control whose effect is nothing at all is worse than no control.
     */
    @Test
    fun `a book with no progress cannot discard it`() {
        render(book = book(progress = null))
        openMenu()

        composeRule.onNodeWithText("Discard progress").assertIsNotEnabled()
    }

    /** The web client needs an address, and without one the row is disabled rather than a dead tap. */
    @Test
    fun `the web client row needs a server address`() {
        render(webUrl = null)
        openMenu()
        composeRule.onNodeWithText("Go to web client").assertIsNotEnabled()

        composeRule.onNodeWithText("Keep it").assertDoesNotExist()
    }

    @Test
    fun `the web client row opens the url it was given`() {
        val opened = mutableListOf<String>()
        render(webUrl = "https://books.example/item/book-1", onOpenWebClient = { opened += it })
        openMenu()

        composeRule.onNodeWithText("Go to web client").assertIsEnabled().performClick()

        assertEquals(listOf("https://books.example/item/book-1"), opened)
    }

    /** More info shows the identifiers the screen itself has no room for. */
    @Test
    fun `more info shows the identifiers`() {
        render()
        openMenu()

        composeRule.onNodeWithText("More info").performClick()

        composeRule.onNodeWithText("9780000000001").assertIsDisplayed()
        composeRule.onNodeWithText("book-1").assertIsDisplayed()
    }

    /** The history opens, and an empty one says so rather than showing a blank sheet. */
    @Test
    fun `history opens`() {
        render()
        openMenu()

        composeRule.onNodeWithText("History").performClick()

        composeRule.onNodeWithText(
            "Nothing yet. Playing, seeking, changing chapter or setting a sleep timer will appear here.",
        ).assertIsDisplayed()
    }

    /**
     * PRODUCT_SPEC DL-001 criterion 1 — the download button exists only where the server grants it.
     *
     * Both directions are asserted, in two tests because `setContent` runs once per test. Either one alone
     * would pass against a bug: a gate that hid the button from everybody satisfies the negative case, and a
     * gate wired to a constant `true` satisfies the positive one.
     */
    @Test
    fun `an account that may download sees the button`() {
        render(canDownload = true)

        composeRule.onNodeWithContentDescription("Download").assertIsDisplayed()
    }

    /** Absent, not merely disabled: for this account it will never work, whatever this app ships. */
    @Test
    fun `an account that may not download does not see the button`() {
        render(canDownload = false)

        composeRule.onNodeWithTag(BOOK_DOWNLOAD_BUTTON).assertDoesNotExist()
    }

    /**
     * PRODUCT_SPEC DL-001 — the control cycles, and each state's label names what a tap *does*.
     *
     * The labels are the assertion rather than the icons, because the label is what a TalkBack user hears
     * and it is the only thing that distinguishes *cancel* — which keeps the partial download — from
     * *remove*, which deletes it. An icon swap with a stale description would be silently wrong for exactly
     * the people who cannot see the icon.
     */
    @Test
    fun `a download in flight offers to cancel it`() {
        render(download = DownloadButtonState.Downloading(progress = 0.4f))

        composeRule.onNodeWithContentDescription("Cancel the download").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Download").assertDoesNotExist()
    }

    @Test
    fun `a downloaded book offers to remove it`() {
        render(download = DownloadButtonState.Downloaded)

        composeRule.onNodeWithContentDescription("Remove the download").assertIsDisplayed()
    }

    /** A stopped download says so, rather than looking like one that was never started. */
    @Test
    fun `a failed download offers a retry`() {
        render(download = DownloadButtonState.Failed)

        composeRule.onNodeWithContentDescription("Retry the download").assertIsDisplayed()
    }

    /**
     * PRODUCT_SPEC 21 — removing is the only tap on this screen that deletes audio, so it asks first.
     *
     * The body's four clauses are asserted in full because each one answers a different fear: the files go,
     * the *server* is untouched, the position survives, and another profile's copy on the same device is not
     * collateral damage (DL-003 criterion 5).
     */
    @Test
    fun `removing a download asks first, and says what it does`() {
        var removed = 0
        render(download = DownloadButtonState.Downloaded, onRemoveDownload = { removed++ })

        composeRule.onNodeWithTag(BOOK_DOWNLOAD_BUTTON).performClick()

        composeRule.onNodeWithText("Remove this download?").assertIsDisplayed()
        composeRule.onNodeWithText(
            "The audio files come off this device and the space is freed. Nothing is deleted on your " +
                "server, your position is kept, and you can download it again whenever you like. If another " +
                "profile on this phone downloaded the same book, their copy stays.",
        ).assertIsDisplayed()
        assertEquals(0, removed, "nothing happens until it is confirmed")

        composeRule.onNodeWithText("Remove").performClick()
        assertEquals(1, removed)
    }

    /**
     * PRODUCT_SPEC 21 — a refusal reaches the user.
     *
     * Every failure the download path can produce — no space, a permission the server revoked, a book with
     * no files — arrived at a `StateFlow` nothing rendered until this. A button that appears to do nothing
     * is the worst of the available outcomes, and it is invisible to every test that only asks whether the
     * ViewModel computed the right thing.
     */
    @Test
    fun `a refusal is shown rather than swallowed`() {
        render(message = BookMessage.Failed("There is not enough space for this book."))

        composeRule.onNodeWithText("There is not enough space for this book.").assertIsDisplayed()
    }

    /** Every other state acts immediately: only removal is destructive, and only removal asks. */
    @Test
    fun `starting a download does not ask`() {
        val taps = mutableListOf<DownloadButtonState>()
        render(download = DownloadButtonState.NotDownloaded, onDownloadClicked = { taps += it })

        composeRule.onNodeWithTag(BOOK_DOWNLOAD_BUTTON).performClick()

        assertEquals(listOf<DownloadButtonState>(DownloadButtonState.NotDownloaded), taps)
        composeRule.onNodeWithText("Remove this download?").assertDoesNotExist()
    }

    private fun openMenu() = composeRule.onNodeWithTag(BOOK_OVERFLOW_BUTTON).performClick()

    private fun render(
        book: Book = book(progress(position = 40.minutes, isFinished = false)),
        webUrl: String? = "https://books.example/item/book-1",
        canDownload: Boolean = true,
        download: DownloadButtonState = DownloadButtonState.NotDownloaded,
        onFinishedChanged: (Boolean) -> Unit = {},
        onDiscardProgress: () -> Unit = {},
        onOpenWebClient: (String) -> Unit = {},
        onDownloadClicked: (DownloadButtonState) -> Unit = {},
        onRemoveDownload: () -> Unit = {},
        onManageDownloads: () -> Unit = {},
        message: BookMessage? = null,
    ) {
        composeRule.setContent {
            BookScreen(
                uiState = BookUiState.Loaded(book),
                // Nothing playing, which is how this screen is most often read.
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
                menu = BookMenuState(webUrl = webUrl, canDownload = canDownload, download = download),
                actions = BookActions(
                    onPlay = {},
                    onTogglePlayPause = {},
                    onFinishedChanged = onFinishedChanged,
                    onDiscardProgress = onDiscardProgress,
                    onOpenWebClient = onOpenWebClient,
                    onDownloadClicked = onDownloadClicked,
                    onRemoveDownload = onRemoveDownload,
                    onManageDownloads = onManageDownloads,
                ),
                onNavigateUp = {},
                message = message,
            )
        }
    }

    private fun progress(position: Duration, isFinished: Boolean) = MediaProgress(
        serverId = SERVER,
        profileId = ProfileId("profile-1"),
        bookId = BOOK,
        position = position,
        duration = 11.hours,
        isFinished = isFinished,
        updatedAt = Instant.ofEpochMilli(1_000),
        hasUnsyncedChanges = false,
    )

    private fun book(progress: MediaProgress?) = Book(
        serverId = SERVER,
        id = BOOK,
        libraryId = LibraryId("lib-fiction"),
        title = "The Salt Harbour",
        subtitle = null,
        authors = emptyList(),
        narrators = emptyList(),
        seriesMemberships = emptyList(),
        duration = 11.hours,
        description = null,
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
        progress = progress,
        localAvailability = LocalAvailability.NotDownloaded,
    )

    private companion object {
        val SERVER = ServerId("server-1")
        val BOOK = LibraryItemId("book-1")
    }
}
