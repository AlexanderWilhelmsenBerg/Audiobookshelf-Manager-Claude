package com.example.shelfplayer.feature.home

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.example.shelfplayer.core.designsystem.theme.BookWaveDesign
import com.example.shelfplayer.core.designsystem.theme.LocalBookWaveDesign
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.library.Series
import com.example.shelfplayer.core.model.library.SeriesMembership
import com.example.shelfplayer.domain.library.HomeShelves
import com.example.shelfplayer.domain.library.SeriesProgress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import kotlin.test.assertFalse
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
    fun `editorial resume and its compact neighbour show the whole title`() {
        val second = first.copy(id = LibraryItemId("second"), title = "A Journey Through the Lantern Keeper's Almanac")
        render(BookWaveDesign.Editorial, shelves.copy(continueListening = listOf(first, second)))
        assertWholeTitle(first.title)
        assertWholeTitle(second.title)
    }

    @Test
    fun `minimal book shelf shows the whole book and membership titles`() {
        render(BookWaveDesign.Minimal, shelves.copy(recentlyAdded = listOf(first)))
        assertWholeTitle(first.title)
        assertWholeTitle(series.name)
    }

    @Test
    fun `continue series shows the whole series and next book titles`() {
        val progress = SeriesProgress(series, first, finishedCount = 1, bookCount = 4, lastPlayedAt = Instant.EPOCH)
        render(BookWaveDesign.Editorial, shelves.copy(continueSeries = listOf(progress)))
        assertWholeTitle(series.name)
        assertWholeTitle(first.title)
    }

    private fun render(design: BookWaveDesign, content: HomeShelves) {
        compose.setContent {
            CompositionLocalProvider(LocalBookWaveDesign provides design) {
                LazyColumn { homeShelves(content, {}, {}, {}) }
            }
        }
    }

    private fun assertWholeTitle(title: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse(layout.hasVisualOverflow, title)
        assertTrue((0 until layout.lineCount).none { layout.isLineEllipsized(it) }, title)
        assertTrue(layout.getLineEnd(layout.lineCount - 1) == title.length, title)
    }
}
