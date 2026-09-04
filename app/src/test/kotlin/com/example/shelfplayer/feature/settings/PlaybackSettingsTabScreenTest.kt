package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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

        val note = "Fiction: your server has not said, so 10 s is used until it does"
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

    /**
     * PRODUCT_SPEC DL-004 / ADR-0018 decision 5 — three switches, and Wi-Fi is not one of them.
     *
     * The hint is asserted because the section is otherwise open to exactly the wrong reading: three
     * switches under a heading about data, all off by default except one, look like they control whether
     * the app has a network at all. They do not — Wi-Fi is always used — and the only place that is said is
     * the hint.
     */
    @Test
    fun `the network section offers cellular per category, and says Wi-Fi is always used`() {
        render(libraries = listOf(library("Fiction", 10.seconds)))

        // The last row first: scrolling to it brings the whole section into the viewport, and the header
        // scrolled to on its own leaves its rows below the fold in a `LazyColumn`.
        scrollTo("Download next book over mobile data")
        composeRule.onNodeWithText("Wi-Fi is always used when it is available", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Stream over mobile data").assertIsDisplayed()
        composeRule.onNodeWithText("Download over mobile data").assertIsDisplayed()
        composeRule.onNodeWithText("Download next book over mobile data").assertIsDisplayed()
    }

    /**
     * The defaults, on the screen: streaming on, both downloads off.
     *
     * Asserted here as well as in `NetworkPolicyTest` because this is the half a user sees. A model whose
     * defaults were right and a screen that rendered them inverted would pass the other test.
     */
    @Test
    fun `the defaults show streaming on and downloads off`() {
        render(libraries = listOf(library("Fiction", 10.seconds)))

        scrollTo("Download next book over mobile data")
        composeRule.onNodeWithText("Stream over mobile data").assertIsOn()
        composeRule.onNodeWithText("Download over mobile data").assertIsOff()
        composeRule.onNodeWithText("Download next book over mobile data").assertIsOff()
    }

    /**
     * PRODUCT_SPEC PLAY-002 — *Keep sound in the headset* is on the screen, above what the car does.
     *
     * Both halves asserted, because the defect this guards against is the one R-100 recorded: a setting
     * that exists in the model, is wired through the ViewModel, and is never drawn. The order matters too —
     * the switch is the answer to the section below it, and a listener who finds only the car section never
     * learns the switch exists.
     */
    @Test
    fun `the headset section offers keeping the sound in the headset`() {
        render(libraries = listOf(library("Fiction", 10.seconds)))

        scrollTo("Keep sound in the headset")
        composeRule.onNodeWithText("Headset").assertIsDisplayed()
        composeRule.onNodeWithText("Keep sound in the headset").assertIsDisplayed()
        composeRule.onNodeWithText("leave the book in whatever headset", substring = true).assertIsDisplayed()
    }

    /**
     * Off unless chosen, and the hint states the two limits rather than leaving them to be discovered.
     *
     * On means the app pins a route the platform may decline, and it will not move audio *into* a headset
     * that is merely connected. A switch that promised either would be lying about what `HeadsetHold` does.
     */
    @Test
    fun `keeping the sound in the headset is off by default and says what it will not do`() {
        render(libraries = listOf(library("Fiction", 10.seconds)))

        scrollTo("Keep sound in the headset")
        composeRule.onNodeWithText("Keep sound in the headset").assertIsOff()
        composeRule.onNodeWithText("merely connected", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("system can still overrule the app", substring = true).assertIsDisplayed()
    }

    /** Pressing the row reports the change, and a screen already showing it on renders it on. */
    @Test
    fun `the headset switch reports its change and reflects a stored value`() {
        val changes = mutableListOf<Boolean>()
        render(
            libraries = listOf(library("Fiction", 10.seconds)),
            settings = PlaybackSettings.Default.copy(keepSoundInHeadset = true),
            onKeepSoundInHeadsetChanged = changes::add,
        )

        scrollTo("Keep sound in the headset")
        composeRule.onNodeWithText("Keep sound in the headset").assertIsOn()
        composeRule.onNodeWithText("Keep sound in the headset").performClick()

        assertEquals(listOf(false), changes)
    }

    /** The tab is a `LazyColumn`, so a row below the viewport does not exist to be asserted on yet. */
    private fun scrollTo(text: String) =
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))

    private fun render(
        libraries: List<Library>,
        settings: PlaybackSettings = PlaybackSettings.Default,
        onKeepSoundInHeadsetChanged: (Boolean) -> Unit = {},
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
                        onKeepSoundInHeadsetChanged = onKeepSoundInHeadsetChanged,
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
