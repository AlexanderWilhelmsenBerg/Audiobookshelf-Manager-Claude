package com.example.shelfplayer.feature.home

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.core.designsystem.theme.BookWaveDesign
import com.example.shelfplayer.core.designsystem.theme.LocalBookWaveDesign
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.library.Series
import com.example.shelfplayer.core.model.library.SeriesMembership
import com.example.shelfplayer.core.model.settings.ShelfCardAppearance
import com.example.shelfplayer.domain.library.HomeShelves
import com.example.shelfplayer.domain.library.SeriesProgress
import com.example.shelfplayer.ui.LocalShelfCardAppearance
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1100dp", fontScale = 1.5f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeShelfTitleLayoutScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val series = Series(book().serverId, SeriesId("s1"), "The Collected Histories of the Northern Isles")
    private val first = book().copy(
        title = "The Lighthouse at the Far End of the Winter Harbour",
        seriesMemberships = listOf(SeriesMembership(series, SeriesSequence.parse("1"), true)),
    )
    private val shelves = HomeShelves.Empty.copy(totalBookCount = 1)

    @Test
    fun `editorial resume and its compact neighbour limit titles to two lines`() {
        val second = first.copy(id = LibraryItemId("second"), title = "A Journey Through the Lantern Keeper's Almanac")
        render(BookWaveDesign.Editorial, shelves.copy(continueListening = listOf(first, second)))
        assertTrue(textLayout(first.title).lineCount <= 2)
        assertTrue(textLayout(second.title).lineCount <= 2)
    }

    @Test
    fun `minimal book cards reserve the same height for long and short titles`() {
        val second = first.copy(id = LibraryItemId("second"), title = "Tide", seriesMemberships = emptyList())
        render(BookWaveDesign.Minimal, shelves.copy(recentlyAdded = listOf(first, second)))

        val longCard = cardBounds(first.title)
        val shortCard = cardBounds(second.title)
        assertEquals(120.dp, longCard.right - longCard.left)
        assertEquals(longCard.bottom - longCard.top, shortCard.bottom - shortCard.top)
        assertTrue(textLayout(first.title).isLineEllipsized(1))
    }

    @Test
    fun `a horizontal series card places its bounded labels beside the cover`() {
        val progress = SeriesProgress(series, first, finishedCount = 1, bookCount = 4, lastPlayedAt = Instant.EPOCH)
        render(
            BookWaveDesign.Editorial,
            shelves.copy(continueSeries = listOf(progress)),
            ShelfCardAppearance(textBesideCover = true, bordersVisible = false),
        )
        val card = cardBounds(series.name)
        val name = compose.onNodeWithText(series.name, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(name.left >= card.left + 80.dp)
        assertTrue(textLayout(series.name).isLineEllipsized(0))
        assertTrue(textLayout(first.title).isLineEllipsized(0))
    }

    private fun cardBounds(title: String) =
        compose.onNode(hasText(title) and hasClickAction()).getUnclippedBoundsInRoot()

    private fun render(
        design: BookWaveDesign,
        content: HomeShelves,
        appearance: ShelfCardAppearance = ShelfCardAppearance(),
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalBookWaveDesign provides design,
                LocalShelfCardAppearance provides appearance,
            ) {
                LazyColumn { homeShelves(content, {}, {}, {}) }
            }
        }
    }

    private fun textLayout(title: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }
}
