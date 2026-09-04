package com.example.shelfplayer.feature.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.domain.library.HomeShelves
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC 16.2 — swiping between the places the navigation capsule offers.
 *
 * Its own file rather than more cases in `HomeScreenTest`, which detekt's `LargeClass` limit had already
 * caught. The split is the right one anyway: everything here is about a gesture, and every assertion is
 * about what `onAxisChanged` receives — including the two cases where the answer is *nothing*.
 *
 * The fixtures are shared with `HomeScreenTest` through `HomeScreenFixtures.kt`, so the two files cannot
 * disagree about what the shelf looks like.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class HomeGestureScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * A swipe towards the left reveals the next place, the way a pager does.
     *
     * Driven through the same `onAxisChanged` the capsule's tabs call, so the two affordances cannot drift:
     * whatever a tap does, a swipe does.
     */
    @Test
    fun `swiping left moves to the next axis`() {
        var axis: HomeAxis? = null
        compose.setContent {
            HomeScreen(
                uiState = state(axis = HomeAxis.Books),
                actions = noActions().copy(onAxisChanged = { axis = it }),
            )
        }

        compose.onNodeWithTag(HOME_AXIS_LIST_TEST_TAG).performTouchInput { swipeLeft() }

        assertEquals(HomeAxis.Series, axis)
    }

    /** And towards the right, the previous one. */
    @Test
    fun `swiping right moves to the previous axis`() {
        var axis: HomeAxis? = null
        compose.setContent {
            HomeScreen(
                // Groups supplied because the tagged list is what carries the gesture, and an axis with
                // nothing in it renders an empty state instead.
                uiState = state(axis = HomeAxis.Authors, groups = listOf(genreGroup("Ursula Le Guin"))),
                actions = noActions().copy(onAxisChanged = { axis = it }),
            )
        }

        compose.onNodeWithTag(HOME_AXIS_LIST_TEST_TAG).performTouchInput { swipeRight() }

        assertEquals(HomeAxis.Series, axis)
    }

    /**
     * **A drag that begins on a carousel scrolls the carousel**, and does not change the axis.
     *
     * `SwipeBetween.kt` argues at length that this is safe — that a `LazyRow` under the finger wins its own
     * touch slop and *consumes* the change, and `detectHorizontalDragGestures` hangs off
     * `awaitTouchSlopOrCancellation`, which cancels the moment it sees a consumed change. That is a claim
     * about Compose's dispatch order, and until now it was only a comment.
     *
     * A screen-level drag handler that stole from the shelves would be the worst kind of regression here:
     * every horizontal flick on a carousel would jump the reader to another axis, and the carousels are the
     * default view.
     *
     * Five books so the row genuinely has somewhere to scroll. A `LazyRow` with nothing beyond its viewport
     * would be a weaker test, because a row that cannot scroll is not obliged to want the gesture.
     */
    @Test
    fun `swiping a shelf carousel scrolls it instead of changing axis`() {
        val books =
            List(SHELF_BOOK_COUNT) { index -> book().copy(id = LibraryItemId("item-$index"), title = "Book $index") }
        var changes = 0
        compose.setContent {
            HomeScreen(
                uiState = state(
                    books = books,
                    booksView = BooksView.Shelves,
                    shelves = HomeShelves(
                        continueListening = emptyList(),
                        continueSeries = emptyList(),
                        recentlyAdded = emptyList(),
                        discover = books,
                        listenAgain = emptyList(),
                        totalBookCount = books.size,
                    ),
                ),
                actions = noActions().copy(onAxisChanged = { changes++ }),
            )
        }

        compose.onNodeWithText("Book 0").performTouchInput { swipeLeft() }

        assertEquals(0, changes, "a flick along a shelf jumped to another axis")
    }

    /**
     * **The row does not wrap**, and this is the case that says so.
     *
     * Swiping right on the first place has nowhere to go. Wrapping to the last one would contradict the
     * capsule it is driving, which shows the reader a position in a row of four.
     */
    @Test
    fun `swiping right on the first axis does nothing`() {
        var changes = 0
        compose.setContent {
            HomeScreen(
                uiState = state(axis = HomeAxis.Books),
                actions = noActions().copy(onAxisChanged = { changes++ }),
            )
        }

        compose.onNodeWithTag(HOME_AXIS_LIST_TEST_TAG).performTouchInput { swipeRight() }

        assertEquals(0, changes)
    }

    /**
     * **A short drag springs back and changes nothing**, which is the owner's fourth ask in as many words:
     * *"pull a little and releasing and it won't switch page, just snap back"*.
     *
     * The drag is a third of the way and then lifts, with no velocity to carry it — `moveTo` and `up`
     * rather than `swipeLeft`, which flings. A pager settles to whichever page is nearer, so the test is
     * that the axis was never reported changed rather than that it changed and came back.
     *
     * The threshold gesture this replaces could not express the case at all: it compared total distance
     * on release against 56dp, so a slow drag of half the screen committed exactly like a flick.
     */
    @Test
    fun `a short drag springs back without changing the axis`() {
        var changes = 0
        compose.setContent {
            HomeScreen(
                uiState = state(axis = HomeAxis.Books),
                actions = noActions().copy(onAxisChanged = { changes++ }),
            )
        }

        compose.onNodeWithTag(HOME_AXIS_LIST_TEST_TAG).performTouchInput {
            down(centerRight)
            moveTo(centerRight - Offset(width * 0.28f, 0f))
            up()
        }
        compose.waitForIdle()

        assertEquals(0, changes, "a drag that did not reach half way must settle back where it started")
    }

    /**
     * **The page next door is composed but not *announced*.**
     *
     * A pager composes its neighbours so a drag reveals real content immediately, and composed means
     * present in the semantics tree — which is what TalkBack reads. Home's not-yet-loaded axes render a
     * loading state whose title is a polite live region, so without `offscreenPage` three of them would
     * announce "Loading your books" over whatever the reader was actually on.
     *
     * Two screen tests found this before a person did, by starting to fail for a reason that had nothing
     * to do with them. Asserted here on purpose so the next change to the pager cannot quietly undo it.
     */
    @Test
    fun `the pages either side contribute nothing to the semantics tree`() {
        compose.setContent {
            HomeScreen(uiState = state(axis = HomeAxis.Books), actions = noActions())
        }

        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .let { live -> assertTrue(live.size <= 1, "an off-screen page is announcing itself: $live") }
    }

    private companion object {
        /** Enough books that the shelf carousel genuinely has somewhere to scroll. */
        const val SHELF_BOOK_COUNT = 5
    }
}
