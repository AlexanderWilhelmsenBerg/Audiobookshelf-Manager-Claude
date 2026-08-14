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
import com.example.shelfplayer.core.model.library.FinishedRule
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
 * The library note is the part worth testing hardest. ADR-0013's rule is a `max`, so a library can overrule
 * the chosen value — and a setting that is silently overruled is a setting that lies.
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

        composeRule.onNodeWithText("Finished with this much left").assertIsDisplayed()
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
     * The `max` made visible: a library asking for longer says so, and names its own number.
     *
     * Without this the app finishes a book a minute from the end while the setting says thirty seconds, and
     * the listener has no way to find out why.
     */
    @Test
    fun `a library asking for longer than the setting says so`() {
        render(libraries = listOf(library("Fiction", 60.seconds)))
        val note =
            "Fiction asks for 60 s, and the longer of the two wins — so books there are finished with 60 s left."
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(note))

        composeRule.onNodeWithText(note).assertIsDisplayed()
    }

    /**
     * A library asking for *less* changes nothing, so it says nothing.
     *
     * This is the capture server's own case — `markAsFinishedTimeRemaining: 10` against a default of 30 — so
     * it is the note most users would otherwise see, about a rule that never applies to them.
     */
    @Test
    fun `a library asking for less than the setting is not mentioned`() {
        render(libraries = listOf(library("Fiction", 10.seconds)))
        scrollToThreshold()

        composeRule.onNodeWithText("Fiction asks for 10 s", substring = true).assertDoesNotExist()
    }

    /** And a library with no rule at all is not mentioned either — most libraries have none. */
    @Test
    fun `a library with no rule is not mentioned`() {
        render(libraries = listOf(library("Fiction", null)))
        scrollToThreshold()

        composeRule.onNodeWithText("asks for", substring = true).assertDoesNotExist()
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
        finishedRule = FinishedRule(timeRemaining = asks),
    )
}
