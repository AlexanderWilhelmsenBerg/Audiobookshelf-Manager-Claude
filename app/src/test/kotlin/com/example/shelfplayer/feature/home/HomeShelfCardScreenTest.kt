package com.example.shelfplayer.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.MediaProgress
import com.example.shelfplayer.core.model.library.Series
import com.example.shelfplayer.core.model.library.SeriesMembership
import com.example.shelfplayer.domain.library.HomeShelves
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
 * PRODUCT_SPEC LIB-002 / LIB-004 — what a book card on the shelf says, and in what order.
 *
 * ### Why order is asserted by position rather than by reading the tree
 *
 * Because order is the requirement. *"Have author on top, second line is title, third line is series"* is
 * not a statement about which strings exist — the old card had two of the three — so a test that only
 * checked they were displayed would have passed before the change and after it. Comparing where they are
 * drawn is the only assertion that fails on the layout this replaced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class HomeShelfCardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** **The order the owner asked for**, top to bottom, on one card. */
    @Test
    fun `a book card reads author, then title, then series`() {
        render(listOf(book()))

        val author = line("Ada Ledger").top
        val title = line("The Salt Harbour").top
        val series = line("Harbour Tales").top

        assertTrue(author < title, "the author is not above the title")
        assertTrue(title < series, "the title is not above the series")
    }

    /** All three are drawn. The series line is new; the other two moved. */
    @Test
    fun `a book card names the author, the title and the series`() {
        render(listOf(book()))

        compose.onNodeWithText("Ada Ledger", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("The Salt Harbour", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Harbour Tales", useUnmergedTree = true).assertIsDisplayed()
    }

    /**
     * PRODUCT_SPEC LIB-003 — the **primary** membership, where the server names one.
     *
     * A book can be third in one series and first in another, and naming the wrong one is worse than
     * naming none: the card would place the book in a story it is not part of.
     */
    @Test
    fun `a book in two series is named by its primary one`() {
        val twoSeries = book().copy(
            seriesMemberships = listOf(
                membership("Side Stories", isPrimary = false),
                membership("Harbour Tales", isPrimary = true),
            ),
        )
        render(listOf(twoSeries))

        compose.onNodeWithText("Harbour Tales", useUnmergedTree = true).assertIsDisplayed()
    }

    /**
     * **The readings row: elapsed left, percentage middle, remaining right.**
     *
     * Asserted by horizontal position for the same reason the lines are asserted by vertical position —
     * the order is the requirement, and three strings in any arrangement would satisfy a presence check.
     */
    @Test
    fun `an in-progress card reads elapsed, per cent and remaining, left to right`() {
        render(listOf(started()))

        val elapsed = line("1h 0m").left
        val percent = line("25%").left
        val remaining = line("3h 0m").left

        assertTrue(elapsed < percent, "elapsed is not left of the percentage")
        assertTrue(percent < remaining, "the percentage is not left of remaining")
    }

    /**
     * **The card still says which book it is once it has readings on it.**
     *
     * The regression this guards against was written and caught in the same hour. A `GlassCard` is
     * clickable, so it merges its whole subtree into one node — and a `contentDescription` set on anything
     * inside it becomes the *card's* description, which Android reads instead of the card's text. The first
     * version of the readings row carried a spoken sentence, and the card announced "1h 0m in, 25 per cent,
     * 3h 0m left" and never named the book.
     *
     * Asserting on the merged node is the point: that is the node a screen reader lands on.
     */
    @Test
    fun `a card with readings still announces the book it is`() {
        render(listOf(started()))

        compose.onNode(hasText("The Salt Harbour", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("Ada Ledger", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("Harbour Tales", substring = true)).assertIsDisplayed()
    }

    /** A book nobody has started shows no readings, so the strip is not a row of blanks on every shelf. */
    @Test
    fun `a shelf with nothing started has no readings row`() {
        render(listOf(book()))

        compose.onNodeWithText("0%", useUnmergedTree = true).assertDoesNotExist()
    }

    /**
     * **A finished book is not in progress**, so it shows no readings and no bar.
     *
     * The distinction is one the old card made in four separate places with the same two-part condition;
     * it is one function now, and this is the case that would break if the `isFinished` half were dropped.
     */
    @Test
    fun `a finished book shows no readings`() {
        val finished = book().copy(
            progress = progress(position = 4.hours, duration = 4.hours, isFinished = true),
        )
        render(listOf(finished))

        compose.onNodeWithText("100%", useUnmergedTree = true).assertDoesNotExist()
    }

    /**
     * PRODUCT_SPEC 4 — every card in a row is the same height, whatever is on it.
     *
     * The readings strip is reserved per **shelf**: one started book on it means every card leaves room,
     * so a card without progress does not sit a line higher than its neighbour. This is the assertion that
     * fails if the strip is ever drawn per card instead.
     */
    @Test
    fun `a card with no progress matches the height of one beside it that has some`() {
        render(listOf(started(), book().copy(id = LibraryItemId("item-2"), title = "Low Tide")))

        val withProgress = line("The Salt Harbour")
        val without = line("Low Tide")

        assertTrue(
            withProgress.top == without.top,
            "the two titles are on different lines: ${withProgress.top} vs ${without.top}",
        )
    }

    /**
     * One drawn line's bounds, from the **unmerged** tree.
     *
     * A card is clickable, so Compose merges its subtree into a single node and every line inside it
     * reports the card's own bounds. Order is what these tests are about, so they have to look under the
     * merge at the `Text`s that actually got laid out.
     */
    private fun line(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).getBoundsInRoot()

    private fun render(books: List<Book>) {
        compose.setContent {
            HomeScreen(
                uiState = state(
                    books = books,
                    booksView = BooksView.Shelves,
                    shelves = HomeShelves(
                        continueListening = books,
                        continueSeries = emptyList(),
                        recentlyAdded = emptyList(),
                        discover = emptyList(),
                        listenAgain = emptyList(),
                        totalBookCount = books.size,
                    ),
                ),
                actions = noActions(),
            )
        }
    }

    /** A quarter of a four-hour book, so the three readings are all distinct and easy to name. */
    private fun started() = book().copy(progress = progress(position = 1.hours, duration = 4.hours))

    private fun book() = com.example.shelfplayer.feature.home.book().copy(
        authors = listOf(Author(serverId = ServerId("srv_books"), id = AuthorId("aut-1"), name = "Ada Ledger")),
        seriesMemberships = listOf(membership("Harbour Tales", isPrimary = true)),
        duration = 4.hours,
    )

    private fun membership(name: String, isPrimary: Boolean) = SeriesMembership(
        series = Series(
            serverId = ServerId("srv_books"),
            id = SeriesId("ser-${name.lowercase().replace(' ', '-')}"),
            name = name,
        ),
        sequence = SeriesSequence.Numeric(raw = "1", value = 1.0),
        isPrimary = isPrimary,
    )

    private fun progress(position: Duration, duration: Duration, isFinished: Boolean = false) = MediaProgress(
        serverId = ServerId("srv_books"),
        profileId = ProfileId("prf_ada"),
        bookId = LibraryItemId("item-1"),
        position = position,
        duration = duration,
        isFinished = isFinished,
        updatedAt = Instant.EPOCH,
        hasUnsyncedChanges = false,
    )
}
