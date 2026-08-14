package com.example.shelfplayer.feature.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Bookmark
import com.example.shelfplayer.core.model.library.Chapter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC 11.1 / 21 — the affordance whose absence made the feature unusable.
 *
 * A device run reported having *no way to mark a bookmark*. The capability was there; the only route to it
 * was a long press on the player's icon, and nobody found it. These tests exist because the defect was not
 * in the repository, the gateway or the database — every one of those was tested and correct — it was that
 * the screen did not offer the action.
 *
 * So the assertions are about what a listener can **see and press**, which is the only kind of assertion
 * that would have caught it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class BookmarkSheetScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** The button is on the sheet, it names the position it would use, and it works. */
    @Test
    fun `the sheet offers a new bookmark at the current position`() {
        var added = 0
        render(position = 35.minutes + 12.seconds, onAdd = { added++ })

        composeRule.onNodeWithText("New bookmark at 35:12").assertIsDisplayed().performClick()

        assertEquals(1, added)
    }

    /**
     * A second already bookmarked cannot be bookmarked again, and the button says so.
     *
     * Audiobookshelf keys a bookmark by its second, so the server would silently overwrite. The official
     * client hides the button in this state; this one disables it and explains, because a control that
     * disappears is the exact failure these tests were written for.
     */
    @Test
    fun `a position that is already bookmarked says so instead of offering again`() {
        render(position = 31.seconds, bookmarks = listOf(bookmark(31.seconds, "A line worth keeping")))

        composeRule.onNodeWithText("Already bookmarked at 0:31").assertIsDisplayed().assertIsNotEnabled()
    }

    /** A different second in the same book is still offered. */
    @Test
    fun `another position is still offered while one is bookmarked`() {
        render(position = 90.seconds, bookmarks = listOf(bookmark(31.seconds, "Earlier")))

        composeRule.onNodeWithText("New bookmark at 1:30").assertIsEnabled()
    }

    /**
     * The offer is truncated to the second, exactly as the write will be.
     *
     * `Bookmark.roundedFrom` is what both go through, so a label promising `35:12` cannot become a bookmark
     * at `35:12.640` — which would be a position the app could never ask the server to delete.
     */
    @Test
    fun `the offered position is truncated to the second`() {
        render(position = 35.minutes + 12.seconds + 640.milliseconds())

        composeRule.onNodeWithText("New bookmark at 35:12").assertIsDisplayed()
    }

    /** An empty list still offers the button — and points at it rather than describing a hidden gesture. */
    @Test
    fun `an empty sheet points at the button`() {
        render(position = 31.seconds)

        composeRule.onNodeWithText("New bookmark at 0:31").assertIsDisplayed()
        composeRule.onNodeWithText(
            "No bookmarks yet. The button above keeps wherever you are in the book.",
        ).assertIsDisplayed()
    }

    /** A note replaces the chapter line, because it is the thing worth reading. */
    @Test
    fun `a bookmark with a note shows the note, and one without shows its chapter`() {
        render(
            position = Duration.ZERO,
            bookmarks = listOf(bookmark(31.seconds, "The bit about the harbour"), bookmark(20.minutes, "")),
        )

        composeRule.onNodeWithText("The bit about the harbour").assertIsDisplayed()
        composeRule.onNodeWithText("The Survey").assertIsDisplayed()
    }

    private fun Int.milliseconds(): Duration = (this / 1_000.0).seconds

    private fun render(position: Duration, bookmarks: List<Bookmark> = emptyList(), onAdd: () -> Unit = {}) {
        composeRule.setContent {
            BookmarkSheet(
                bookmarks = bookmarks,
                chapters = CHAPTERS,
                position = position,
                actions = BookmarkActions(
                    onAdd = onAdd,
                    onGoTo = {},
                    onRename = { _, _ -> },
                    onRemove = {},
                ),
                onDismiss = {},
            )
        }
    }

    private fun bookmark(at: Duration, title: String) = Bookmark(
        bookId = BOOK,
        at = at,
        title = title,
        createdAt = Instant.ofEpochMilli(1_000),
    )

    private companion object {
        val BOOK = LibraryItemId("book-salt-harbour")
        val SERVER = ServerId("server-1")

        val CHAPTERS = listOf(
            Chapter(SERVER, BOOK, 0, "Departure", Duration.ZERO, 10.minutes),
            Chapter(SERVER, BOOK, 1, "The Survey", 10.minutes, 30.minutes),
            Chapter(SERVER, BOOK, 2, "Soundings", 30.minutes, 60.minutes),
        )
    }
}
