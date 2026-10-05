package com.example.shelfplayer.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.core.designsystem.theme.ShelfPlayerTheme
import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.MediaProgress
import com.example.shelfplayer.core.model.library.Series
import com.example.shelfplayer.core.model.library.SeriesMembership
import com.example.shelfplayer.feature.browse.BookCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** LIB-002/004, spec2.10/21: actual text layout and card bounds, not existence alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w375dp-h1280dp", fontScale = 1.0f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookRowMetadataScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `unstarted row draws full length below wrapped series`() = assertMetadataFits(finished = null)

    @Test
    fun `finished row draws full completion below wrapped series`() = assertMetadataFits(finished = true)

    @Test
    fun `listening row draws full progress and bar below wrapped series`() = assertMetadataFits(finished = false)

    @Test
    @Config(qualifiers = "w320dp-h1280dp", fontScale = 2.0f)
    fun `large text listening row grows to retain series progress and bar`() = assertMetadataFits(finished = false)

    private fun assertMetadataFits(finished: Boolean?) {
        val fixture = fixtureBook(finished)
        compose.setContent {
            ShelfPlayerTheme(darkTheme = true, dynamicColor = false) {
                Surface {
                    Box(Modifier.padding(16.dp)) {
                        BookCard(fixture, onClick = {}, modifier = Modifier.testTag("book-row"))
                    }
                }
            }
        }
        val card = compose.onNodeWithTag("book-row").getBoundsInRoot()
        val progressText = when (finished) {
            null -> "3h 0m"
            true -> "Finished · 3h 0m"
            false -> "1h 0m in · 2h 0m left · 3h 0m · 33%"
        }
        listOf("A Longer Fixture Series, book 12", progressText).forEach { text ->
            val node = compose.onNodeWithText(text, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse(layout.didOverflowHeight, "Text is vertically clipped: $text")
            assertTrue((0 until layout.lineCount).none { layout.isLineEllipsized(it) })
            assertTrue(layout.getLineEnd(layout.lineCount - 1) == text.length)
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(bounds.top >= card.top && bounds.bottom <= card.bottom, "Text escapes card: $text")
        }
        if (finished == false) {
            val bar = compose.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
                useUnmergedTree = true,
            ).getUnclippedBoundsInRoot()
            assertTrue(bar.top >= card.top && bar.bottom <= card.bottom, "Progress bar escapes card")
        }
    }

    private fun fixtureBook(finished: Boolean?): com.example.shelfplayer.core.model.library.Book {
        val base = book()
        return base.copy(
            title = "Fixture book",
            duration = 3.hours,
            authors = listOf(Author(base.serverId, AuthorId("author"), "Fixture author")),
            seriesMemberships = listOf(
                SeriesMembership(
                    Series(base.serverId, SeriesId("series"), "A Longer Fixture Series"),
                    SeriesSequence.parse("12"),
                    true,
                ),
            ),
            progress = finished?.let {
                MediaProgress(
                    serverId = base.serverId,
                    profileId = ProfileId("profile"),
                    bookId = base.id,
                    position = if (it) 3.hours else 1.hours,
                    duration = 3.hours,
                    isFinished = it,
                    updatedAt = Instant.EPOCH,
                    hasUnsyncedChanges = false,
                )
            },
        )
    }
}
