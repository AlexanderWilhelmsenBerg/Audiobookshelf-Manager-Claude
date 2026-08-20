package com.example.shelfplayer.a11y

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.domain.library.HomeShelves
import com.example.shelfplayer.feature.browse.CoverUrls
import com.example.shelfplayer.feature.browse.LocalCoverUrls
import com.example.shelfplayer.feature.home.homeShelves
import com.example.shelfplayer.feature.player.FullPlayer
import com.example.shelfplayer.feature.player.MiniPlayer
import com.example.shelfplayer.feature.player.PlayerActions
import com.example.shelfplayer.feature.player.SkipControls
import com.example.shelfplayer.playback.PlaybackUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * PRODUCT_SPEC §51 / 2.10 — the accessibility net over the two screens a listener actually lives in.
 *
 * ### Why these two were left out, and why that was the wrong way round
 *
 * The first pass covered every screen with a **destructive** action, on the reasoning that an unlabelled
 * control is worst where a mis-tap cannot be undone. That is true and it is not the whole rule. The player
 * and the shelf are the screens a listener touches every single day, and the player is the one they touch
 * *without looking* — in a pocket, in a car, in the dark. Its controls are icon-only by design, so there is
 * no text to fall back on: an unlabelled transport button is announced as "button" and nothing else, and
 * the listener has no way to find out which one it was except by pressing it.
 *
 * So this closes the gap `docs/risks.md` R-29 named, and the ordering lesson is worth keeping: *screens
 * with destructive actions* and *screens used blind* are two different lists, and the second one is longer.
 *
 * The limits are the ones `AccessibilityAssertions` documents — the semantics tree can say whether a label
 * exists, never whether it is a good one, and nothing here reaches contrast or reading order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PlayerAccessibilityScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `every control in the full player announces itself`() {
        renderFullPlayer()

        composeRule.assertEveryControlIsLabelled()
    }

    @Test
    fun `every control in the full player is big enough to hit`() {
        renderFullPlayer()

        composeRule.assertEveryControlIsBigEnough()
    }

    /**
     * The failed state adds a control, and it is the one a listener most needs to find.
     *
     * Playback has stopped and the only way forward is a retry button that appears nowhere else. A default
     * render never reaches it.
     */
    @Test
    fun `the retry control announces itself when playback has failed`() {
        renderFullPlayer(hasFailed = true)

        composeRule.assertEveryControlIsLabelled()
    }

    /**
     * A running sleep timer relabels a control rather than adding one.
     *
     * The timer button says how long is left, so its label is *computed* — which is exactly the kind that
     * comes back empty when the formatting path has a hole in it.
     */
    @Test
    fun `the sleep timer control still announces itself while it is running`() {
        renderFullPlayer(timer = SleepTimerState(SleepTimerMode.Fixed(30.minutes), 12.minutes, isFading = false))

        composeRule.assertEveryControlIsLabelled()
    }

    /**
     * The mini player is four controls in a strip 64dp tall, which is where a too-small target hides.
     *
     * It is also the only player surface present on every screen, so a defect here is a defect everywhere.
     */
    @Test
    fun `every control in the mini player announces itself and is big enough`() {
        renderMiniPlayer()

        composeRule.assertEveryControlIsLabelled()
        composeRule.assertEveryControlIsBigEnough()
    }

    /**
     * PRODUCT_SPEC 2.10 — the player at a doubled font scale.
     *
     * The transport row is the densest set of controls in the app. If large text is going to shrink a
     * target below the minimum anywhere, it is here.
     */
    @Test
    fun `the player's controls survive a doubled font scale`() {
        // Through `LocalDensity`, not a Robolectric qualifier: Android has no `fontScale` resource
        // qualifier, so `-fontScale2` fails to parse. `BookAccessibilityScreenTest` found that first.
        renderFullPlayer(fontScale = 2f)

        composeRule.assertEveryControlIsLabelled()
        composeRule.assertEveryControlIsBigEnough()
    }

    /**
     * The shelf is a grid of covers, and a cover is an image used as a button.
     *
     * That is the shape most likely to be announced as nothing at all: the tile's only content is a
     * `BookCover`, so unless the row supplies a label there is no text for the merged node to inherit.
     */
    @Test
    fun `every book tile on the shelf announces itself`() {
        renderShelf()

        composeRule.assertEveryControlIsLabelled()
    }

    @Test
    fun `every book tile on the shelf is big enough to hit`() {
        renderShelf()

        composeRule.assertEveryControlIsBigEnough()
    }

    // ---- renders --------------------------------------------------------------------------------------

    private fun renderFullPlayer(
        hasFailed: Boolean = false,
        timer: SleepTimerState = SleepTimerState.Idle,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            AtFontScale(fontScale) {
                FullPlayer(
                    state = playback(hasFailed = hasFailed),
                    timer = timer,
                    actions = PlayerActions(
                        onTogglePlayPause = {},
                        onSeekTo = {},
                        onOpenSpeed = {},
                        onOpenSleepTimer = {},
                        onOpenChapters = {},
                        onCollapse = {},
                    ),
                    skips = SkipControls.Inert,
                )
            }
        }
    }

    /** The one Compose-level way to raise the font scale; there is no resource qualifier for it. */
    @androidx.compose.runtime.Composable
    private fun AtFontScale(scale: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
        val base = LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            LocalDensity provides Density(density = base.density, fontScale = scale),
            content = content,
        )
    }

    private fun renderMiniPlayer() {
        composeRule.setContent {
            Column {
                MiniPlayer(
                    state = playback(),
                    timer = SleepTimerState.Idle,
                    onTogglePlayPause = {},
                    onStop = {},
                    onOpenSleepTimer = {},
                    onExpand = {},
                    skips = SkipControls.Inert,
                )
            }
        }
    }

    private fun renderShelf() {
        composeRule.setContent {
            // `CoverUrls.None` is the offline case — no server address, so no cover URL. Right for this
            // test: it asserts what a tile *announces*, and an image that never loads changes nothing
            // about that. It also keeps the test off the network.
            androidx.compose.runtime.CompositionLocalProvider(LocalCoverUrls provides CoverUrls.None) {
                LazyColumn {
                    homeShelves(
                        shelves = HomeShelves(
                            continueListening = listOf(book("book-1", "The Salt Harbour")),
                            continueSeries = emptyList(),
                            recentlyAdded = listOf(book("book-2", "Tide Tables")),
                            discover = listOf(book("book-3", "Soundings")),
                            listenAgain = emptyList(),
                        ),
                        onBookSelected = {},
                        onSeriesSelected = {},
                    )
                }
            }
        }
    }

    private fun playback(hasFailed: Boolean = false) = PlaybackUiState(
        bookId = LibraryItemId("book-1"),
        title = "The Salt Harbour",
        author = "Marisol Holt",
        artworkUri = null,
        isPlaying = true,
        isLoading = false,
        position = 40.minutes,
        duration = 4.hours,
        chapters = listOf(
            Chapter(SERVER, LibraryItemId("book-1"), 0, "The Ebb", Duration.ZERO, 1.hours),
            Chapter(SERVER, LibraryItemId("book-1"), 1, "The Flood", 1.hours, 2.hours),
        ),
        currentChapter = Chapter(SERVER, LibraryItemId("book-1"), 0, "The Ebb", Duration.ZERO, 1.hours),
        hasFailed = hasFailed,
    )

    private fun book(id: String, title: String) = Book(
        serverId = SERVER,
        id = LibraryItemId(id),
        libraryId = LibraryId("lib-fiction"),
        title = title,
        subtitle = null,
        authors = listOf(Author(SERVER, AuthorId("author-1"), "Marisol Holt")),
        narrators = emptyList(),
        seriesMemberships = emptyList(),
        duration = 4.hours,
        description = null,
        genres = emptyList(),
        tags = emptyList(),
        publishedYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        isExplicit = false,
        isAbridged = false,
        coverPath = null,
        trackCount = 1,
        sizeBytes = 0,
        remoteUpdatedAt = null,
        addedAt = Instant.ofEpochMilli(1_000),
        lastFetchedAt = Instant.ofEpochMilli(0),
        progress = null,
        localAvailability = LocalAvailability.NotDownloaded,
    )

    private companion object {
        val SERVER = ServerId("server-1")
    }
}
