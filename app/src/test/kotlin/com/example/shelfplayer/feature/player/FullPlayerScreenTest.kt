package com.example.shelfplayer.feature.player

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.playback.PlaybackUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * PRODUCT_SPEC PLAY-003 — the chapter bar under the book's, rendered.
 *
 * The bar itself is a shape and cannot be asserted on directly; what can be asserted is everything a
 * listener actually reads off it — the ordinal, the remaining time, and what a screen reader hears.
 */
@RunWith(RobolectricTestRunner::class)
// A phone-sized window rather than Robolectric's default, which is small enough that the full player's
// lower half lays out off-screen and every `assertIsDisplayed` fails for the wrong reason.
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class FullPlayerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `primary transport and History remain directly reachable`() {
        var historyOpens = 0
        render(
            state = playing(position = 35.minutes),
            onOpenHistory = { historyOpens += 1 },
        )

        composeRule.onNodeWithContentDescription("Back 30 seconds").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Forward 30 seconds").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("History").assertIsDisplayed().performClick()

        assertEquals(1, historyOpens)
    }

    @Test
    fun `Bookmarks and Add Bookmark remain reachable from player actions`() {
        var bookmarkOpens = 0
        var bookmarkAdds = 0
        render(
            state = playing(position = 35.minutes),
            onOpenBookmarks = { bookmarkOpens += 1 },
            onAddBookmark = { bookmarkAdds += 1 },
        )

        composeRule.onNodeWithContentDescription("More player actions").performClick()
        composeRule.onNodeWithText("Bookmarks").assertIsDisplayed().performClick()
        assertEquals(1, bookmarkOpens)

        composeRule.onNodeWithContentDescription("More player actions").performClick()
        composeRule.onNodeWithText("Bookmark this spot").assertIsDisplayed().performClick()
        assertEquals(1, bookmarkAdds)
    }

    @Test
    fun `Chapters are direct when chapter metadata exists`() {
        render(playing(position = 35.minutes))

        composeRule.onNodeWithContentDescription("Chapters").assertIsDisplayed()
    }

    @Test
    fun `book and chapter progress have distinct spoken identities`() {
        render(playing(position = 35.minutes))

        composeRule
            .onNodeWithContentDescription("Book position: 35:00 elapsed, 1:25:00 remaining")
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("The Flood, 15:00 left in this chapter")
            .assertIsDisplayed()
    }

    @Test
    fun `ordinary Android Back collapses through the same player action`() {
        var collapses = 0
        var dispatcher: OnBackPressedDispatcher? = null

        composeRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            FullPlayer(
                state = playing(position = 35.minutes),
                timer = SleepTimerState.Idle,
                actions = inertActions(onCollapse = { collapses += 1 }),
            )
        }

        composeRule.runOnIdle {
            checkNotNull(dispatcher).onBackPressed()
        }
        composeRule.waitUntil { collapses == 1 }

        assertEquals(1, collapses)
    }

    @Test
    fun `toolbar collapse reports through the one collapse action`() {
        var collapses = 0
        render(
            state = playing(position = 35.minutes),
            onCollapse = { collapses += 1 },
        )

        composeRule.onNodeWithContentDescription("Close the player").performClick()

        assertEquals(1, collapses)
    }

    /** Thirty-five minutes in is five minutes into the second of three chapters, which runs to fifty. */
    @Test
    fun `the chapter bar shows which chapter and how much of it is left`() {
        render(playing(position = 35.minutes))

        composeRule.onNodeWithText("Chapter 2 of 3").assertIsDisplayed()
        composeRule.onNodeWithText("−15:00").assertIsDisplayed()
    }

    /**
     * The book's own remaining time stays what it was.
     *
     * The two bars answer different questions, and the failure this pins is the one where the second bar
     * is wired to the first's numbers — which would look plausible and be useless.
     */
    @Test
    fun `the book's remaining time is not the chapter's`() {
        render(playing(position = 35.minutes))

        composeRule.onNodeWithText("−1:25:00").assertIsDisplayed()
    }

    /** TalkBack gets a sentence, not a bar and three loose fragments. */
    @Test
    fun `the chapter bar is announced as one reading`() {
        render(playing(position = 35.minutes))

        composeRule.onNodeWithContentDescription("The Flood, 15:00 left in this chapter").assertIsDisplayed()
    }

    /**
     * A book with no chapter metadata, which is common in a self-hosted library.
     *
     * Nothing is drawn at all. An empty bar that will never move reads as a book that is still loading.
     */
    @Test
    fun `a book with no chapters has no chapter bar or direct Chapters action`() {
        render(playing(position = 35.minutes, chapters = emptyList()))

        composeRule.onNodeWithText("Chapter 1 of 1").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Chapters").assertDoesNotExist()
    }

    /**
     * PRODUCT_SPEC PLAY-003 — the chapter bar seeks, and it seeks *inside the chapter*.
     *
     * The device report asked for it and the arithmetic is the part worth pinning: half way along a bar
     * that spans a chapter running from thirty to fifty minutes is **forty** minutes into the book, not
     * halfway through the book. Getting this wrong would send a listener an hour away from where they
     * pointed, from a control that looks precise.
     */
    @Test
    fun `dragging the chapter bar seeks inside the chapter`() {
        var seeked: Duration? = null
        render(playing(position = 35.minutes), onSeekTo = { seeked = it })

        composeRule.onNodeWithContentDescription("The Flood, 15:00 left in this chapter")
            .performSemanticsAction(SemanticsActions.SetProgress) { set -> set(0.5f) }

        assertEquals(40.minutes, seeked)
    }

    /** And the book's bar still spans the book, so the two cannot have been wired to the same range. */
    @Test
    fun `dragging the book bar seeks across the whole book`() {
        var seeked: Duration? = null
        render(playing(position = 35.minutes), onSeekTo = { seeked = it })

        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress), useUnmergedTree = false)
            .onFirst()
            .performSemanticsAction(SemanticsActions.SetProgress) { set -> set(0.5f) }

        assertEquals(1.hours, seeked)
    }

    private fun render(
        state: PlaybackUiState,
        onSeekTo: (Duration) -> Unit = {},
        onOpenHistory: () -> Unit = {},
        onOpenBookmarks: () -> Unit = {},
        onAddBookmark: () -> Unit = {},
        onCollapse: () -> Unit = {},
    ) {
        composeRule.setContent {
            FullPlayer(
                state = state,
                timer = SleepTimerState.Idle,
                actions = inertActions(
                    onSeekTo = onSeekTo,
                    onOpenHistory = onOpenHistory,
                    onOpenBookmarks = onOpenBookmarks,
                    onAddBookmark = onAddBookmark,
                    onCollapse = onCollapse,
                ),
                backEnabled = false,
            )
        }
    }

    private fun inertActions(
        onSeekTo: (Duration) -> Unit = {},
        onOpenHistory: () -> Unit = {},
        onOpenBookmarks: () -> Unit = {},
        onAddBookmark: () -> Unit = {},
        onCollapse: () -> Unit = {},
    ) = PlayerActions(
        onTogglePlayPause = {},
        onSeekTo = onSeekTo,
        onOpenSpeed = {},
        onOpenSleepTimer = {},
        onOpenChapters = {},
        onOpenHistory = onOpenHistory,
        onOpenBookmarks = onOpenBookmarks,
        onAddBookmark = onAddBookmark,
        onCollapse = onCollapse,
    )

    private fun playing(position: Duration, chapters: List<Chapter> = CHAPTERS) = PlaybackUiState(
        bookId = BOOK,
        title = "The Tidewatch Cycle",
        author = "Marisol Holt",
        artworkUri = null,
        isPlaying = true,
        isLoading = false,
        position = position,
        duration = 2.hours,
        chapters = chapters,
        currentChapter = chapters.lastOrNull { it.start <= position },
    )

    private companion object {
        val BOOK = LibraryItemId("tidewatch")
        val SERVER = ServerId("server-1")

        val CHAPTERS = listOf(
            chapter(0, "The Ebb", 0.minutes, 30.minutes),
            chapter(1, "The Flood", 30.minutes, 50.minutes),
            chapter(2, "The Slack", 50.minutes, 2.hours),
        )

        fun chapter(index: Int, title: String, start: Duration, end: Duration) = Chapter(
            serverId = SERVER,
            bookId = BOOK,
            index = index,
            title = title,
            start = start,
            end = end,
        )
    }
}
