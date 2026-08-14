package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-004 / SET-002 — the finished threshold, as a listener meets it.
 *
 * These are screen assertions rather than repository ones on purpose, and the reason is recorded: PR 1 of
 * this closeout shipped a complete, well-tested bookmark feature with **no visible way to create a
 * bookmark**, because every test asked whether a bookmark could be stored and none asked whether one could
 * be made. A setting nobody can find is the same defect.
 *
 * The library lines are the part worth testing hardest. The chosen value is only a **fallback**: where a
 * library on the server sets `markAsFinishedTimeRemaining`, that is what its books use. A setting that is
 * silently overruled is a setting that lies, so each library says what it actually uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PlaybackSettingsTabScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** The section exists, says what it does, and the chosen value is the one shown as chosen. */
    @Test
    fun `the finished threshold is on the tab and shows what is chosen`() {
        render()
        scrollToThreshold()

        composeRule.onNodeWithText("Used where your server sets nothing").assertIsDisplayed()
        chip("30 s").assertIsSelected()
    }

    /** Pressing a chip reports the duration, not a position in a list. */
    @Test
    fun `choosing a different amount reports it`() {
        val chosen = mutableListOf<Duration>()
        render(onChanged = { chosen += it })
        scrollToThreshold()

        chip("90 s").performClick()

        assertEquals(listOf(90.seconds), chosen)
    }

    /**
     * A library that sets its own value names it, and says where it came from.
     *
     * This is the capture server's own case — `markAsFinishedTimeRemaining: 10` — and it is the case where
     * the chips above do nothing at all. Saying so is the whole point of the line.
     */
    @Test
    fun `a library with its own value says what it is and that it came from the server`() {
        render(libraries = listOf(library("Fiction", 10.seconds)))
        val note = "Fiction: 10 s, from your server"
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(note))

        composeRule.onNodeWithText(note).assertIsDisplayed()
    }

    /** A library with no value says the chips apply to it, which is the only case in which they do. */
    @Test
    fun `a library with no value says the setting is used`() {
        render(libraries = listOf(library("Fiction", null)))
        val note = "Fiction: no value on the server, so the one above is used"
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(note))

        composeRule.onNodeWithText(note).assertIsDisplayed()
    }

    /**
     * The inherited value does not move when the chips do.
     *
     * The line is what the *server* says, so pressing 120 s must not change it — and must not make it
     * disappear, which is what an earlier build did when it only listed libraries that differed from the
     * chosen value. On this server that build showed nothing, on exactly the screen where the chips were
     * doing nothing.
     */
    @Test
    fun `pressing a chip does not change what a library reports`() {
        render(
            settings = PlaybackSettings.Default.copy(finishedThreshold = 120.seconds),
            libraries = listOf(library("Fiction", 10.seconds)),
        )
        val note = "Fiction: 10 s, from your server"
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(note))

        composeRule.onNodeWithText(note).assertIsDisplayed()
        chip("120 s").assertIsSelected()
    }

    /**
     * The tab is a `LazyColumn`, so a row below the viewport does not exist to be asserted on yet.
     *
     * Scrolling to the section is therefore part of the arrangement rather than part of the assertion — and
     * it is also the honest version of the question these tests ask, which is whether somebody scrolling
     * through Settings finds this.
     */
    private fun scrollToThreshold() {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(FINISHED_THRESHOLD_CHIPS))
    }

    /**
     * One chip of the finished row, by its label.
     *
     * The ancestor filter is what makes this unambiguous: the two skip rows offer the same eight labels, so
     * "90 s" alone matches three nodes on this tab. See the tag's own note in `PlaybackSettingsTab`.
     */
    private fun chip(label: String) = composeRule.onAllNodesWithText(label)
        .filterToOne(hasAnyAncestor(hasTestTag(FINISHED_THRESHOLD_CHIPS)))

    private fun render(
        settings: PlaybackSettings = PlaybackSettings.Default,
        libraries: List<Library> = emptyList(),
        onChanged: (Duration) -> Unit = {},
    ) {
        composeRule.setContent {
            LazyColumn {
                playbackTab(
                    settings = settings,
                    libraries = libraries,
                    actions = PlaybackSettingsActions(
                        onSpeedChanged = {},
                        onSkipsChanged = {},
                        onAutoRewindChanged = {},
                        onBufferChanged = {},
                        onFinishedThresholdChanged = onChanged,
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
