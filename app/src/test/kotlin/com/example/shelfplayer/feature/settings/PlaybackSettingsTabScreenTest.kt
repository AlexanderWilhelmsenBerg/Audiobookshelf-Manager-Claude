package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibraryKind
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-004 — the Finished section, which is a **reading rather than a control**.
 *
 * The app has no finished threshold of its own: the rule is the library's, read from the server. So the thing
 * to test is not whether a listener can change it — nobody can, from here — but whether the screen can
 * *explain the app's behaviour* and say where the number lives. A listener who watches a book finish with a
 * minute left has to be able to find out that their library asked for a minute.
 *
 * These are screen assertions for the reason PR 1 of this closeout taught: it shipped a fully tested bookmark
 * feature with no visible way to make a bookmark, because every test asked whether the data was right and none
 * asked what was on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PlaybackSettingsTabScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** A library reports the number in force for its books, in the listener's terms rather than the API's. */
    @Test
    fun `a library reports the threshold its books actually use`() {
        render(libraries = listOf(library("Fiction", 10.seconds)))

        scrollTo("Fiction: finished with 10 s left")
        composeRule.onNodeWithText("Fiction: finished with 10 s left").assertIsDisplayed()
    }

    /**
     * A library the app has no setting for says so, and names the fallback it is using instead.
     *
     * "No value" on its own would leave the listener unable to predict anything. The number is the point.
     */
    @Test
    fun `a library with no value names the fallback in force`() {
        render(libraries = listOf(library("Fiction", null)))

        val note = "Fiction: your server has not said, so 30 s is used until it does"
        scrollTo(note)
        composeRule.onNodeWithText(note).assertIsDisplayed()
    }

    /** Every library is listed, because the one that surprises you is the one you need to find. */
    @Test
    fun `every library is listed`() {
        render(libraries = listOf(library("Fiction", 10.seconds), library("Non-fiction", 90.seconds)))

        scrollTo("Non-fiction: finished with 90 s left")
        composeRule.onNodeWithText("Fiction: finished with 10 s left").assertIsDisplayed()
        composeRule.onNodeWithText("Non-fiction: finished with 90 s left").assertIsDisplayed()
    }

    /**
     * The section says where the number is changed, which is the only actionable thing on it.
     *
     * Asserted on the exact phrase a listener would look for, because a hint that describes the rule without
     * saying where it lives leaves them with nowhere to go.
     */
    @Test
    fun `the hint says the value lives in the web interface, per library`() {
        render(libraries = listOf(library("Fiction", 10.seconds)))

        scrollTo("Finished")
        composeRule
            .onNodeWithText("Audiobookshelf web interface", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("mark as finished when time remaining is", substring = true).assertIsDisplayed()
    }

    /** No libraries read yet is a state the screen has to survive without implying a rule. */
    @Test
    fun `no libraries says so rather than showing nothing`() {
        render(libraries = emptyList())

        val note = "No libraries have been read from your server yet, so nothing here can be shown."
        scrollTo(note)
        composeRule.onNodeWithText(note).assertIsDisplayed()
    }

    /** There is nothing to press: the app keeps no threshold, so no chip row offers one. */
    @Test
    fun `there is no threshold control`() {
        render(libraries = listOf(library("Fiction", 10.seconds)))

        scrollTo("Finished")
        composeRule.onNodeWithText("Used where your server sets nothing").assertDoesNotExist()
        composeRule.onNodeWithText("Finished with this much left").assertDoesNotExist()
    }

    /** The tab is a `LazyColumn`, so a row below the viewport does not exist to be asserted on yet. */
    private fun scrollTo(text: String) =
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))

    private fun render(libraries: List<Library>) {
        composeRule.setContent {
            LazyColumn {
                playbackTab(
                    settings = PlaybackSettings.Default,
                    libraries = libraries,
                    actions = PlaybackSettingsActions(
                        onSpeedChanged = {},
                        onSkipsChanged = {},
                        onAutoRewindChanged = {},
                        onBufferChanged = {},
                        onAutoPlayChanged = {},
                    ),
                )
            }
        }
    }

    private fun library(name: String, asks: Duration?) = Library(
        serverId = ServerId("server-1"),
        id = LibraryId("lib-${name.lowercase()}"),
        name = name,
        kind = LibraryKind.Book,
        displayOrder = 0,
        bookCount = 12,
        remoteUpdatedAt = null,
        lastFetchedAt = Instant.ofEpochMilli(0),
        finishedWhenRemaining = asks,
    )
}
