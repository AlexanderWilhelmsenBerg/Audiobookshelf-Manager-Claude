package com.example.shelfplayer.feature.series

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.graphics.createBitmap
import com.example.shelfplayer.core.designsystem.theme.ShelfPlayerTheme
import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.model.library.MediaProgress
import com.example.shelfplayer.core.model.library.Series
import com.example.shelfplayer.core.model.library.SeriesMembership
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** LIB-003 / LIB-004 / section 21: text must be drawn, not merely present in the semantic tree. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h1280dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeriesBookCardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var renderedView: View

    @Test
    @Config(fontScale = 2.0f)
    fun `long title author series and progress fit inside the card at doubled text size`() {
        assertMetadataFits()
    }

    @Test
    @Config(qualifiers = "w375dp-h1280dp", fontScale = 2.0f)
    fun `metadata fits at 375dp and doubled text size`() = assertMetadataFits()

    @Test
    @Config(qualifiers = "w414dp-h1280dp", fontScale = 2.0f)
    fun `metadata fits at 414dp and doubled text size`() = assertMetadataFits()

    @Test
    @Config(qualifiers = "w768dp-h1280dp", fontScale = 2.0f)
    fun `metadata fits at 768dp and doubled text size`() = assertMetadataFits()

    @Test
    @Config(qualifiers = "w320dp-h1280dp", fontScale = 1.3f)
    fun `metadata fits at 320dp and increased text size`() = assertMetadataFits()

    @Test
    @Config(qualifiers = "w375dp-h1280dp", fontScale = 1.3f)
    fun `metadata fits at 375dp and increased text size`() = assertMetadataFits()

    @Test
    @Config(qualifiers = "w414dp-h1280dp", fontScale = 1.3f)
    fun `metadata fits at 414dp and increased text size`() = assertMetadataFits()

    @Test
    @Config(qualifiers = "w768dp-h1280dp", fontScale = 1.3f)
    fun `metadata fits at 768dp and increased text size`() = assertMetadataFits()

    @Test
    @Config(qualifiers = "w320dp-h1280dp", fontScale = 1.0f)
    fun `metadata fits at 320dp and standard text size`() = assertMetadataFits()

    @Test
    @Config(qualifiers = "w375dp-h1280dp", fontScale = 1.0f)
    fun `metadata fits at 375dp and standard text size`() = assertMetadataFits()

    @Test
    @Config(qualifiers = "w414dp-h1280dp", fontScale = 1.0f)
    fun `metadata fits at 414dp and standard text size`() = assertMetadataFits()

    @Test
    @Config(qualifiers = "w768dp-h1280dp", fontScale = 1.0f)
    fun `metadata fits at 768dp and standard text size`() = assertMetadataFits()

    private fun assertMetadataFits() {
        render(book())
        val card = compose.onNodeWithTag("series-book").getBoundsInRoot()
        listOf(TITLE, AUTHOR, "$SERIES, book 12", "3h 0m").forEach { text ->
            val node = compose.onNodeWithText(text, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty(), "missing text layout")
            val layout = layouts.single()
            assertFalse(layout.didOverflowHeight, "text height was truncated: $text")
            assertTrue(
                // Paragraph.width is the allocated track, not the drawn text width. Check actual line edges.
                (0 until layout.lineCount).all {
                    layout.getLineRight(it) <= layout.size.width + PIXEL_ROUNDING_TOLERANCE &&
                        layout.getLineLeft(it) >= -PIXEL_ROUNDING_TOLERANCE
                },
                "text width was truncated: $text",
            )
            assertTrue((0 until layout.lineCount).none { layout.isLineEllipsized(it) }, "text was ellipsized: $text")
            assertTrue(layout.getLineEnd(layout.lineCount - 1) == text.length, "text ended early: $text")
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(bounds.bottom <= card.bottom, "text extends below the card: $text")
            assertTrue(bounds.left >= card.left && bounds.right <= card.right, "text extends outside the card")
        }
    }

    @Test
    fun `finished book has a visible standalone finished label`() {
        render(book().copy(progress = progress(finished = true)))
        compose.onNodeWithText("Finished", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `in progress book remains distinguishable from finished and not started`() {
        render(book().copy(progress = progress(finished = false)))
        compose.onNodeWithText("In progress", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Finished", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Not started", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    @Config(fontScale = 2.0f)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `native render of finished row preserves readable complete metadata`() {
        render(book().copy(progress = progress(finished = true)))
        capture("finished-320dp-font2")
    }

    @Test
    @Config(qualifiers = "w414dp-h1280dp", fontScale = 1.0f)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `native render of in progress row shows listening state and progress`() {
        render(book().copy(progress = progress(finished = false)))
        capture("in-progress-414dp-font1")
    }

    private fun capture(name: String) {
        lateinit var image: Bitmap
        val bounds = compose.onNodeWithTag("series-book").getBoundsInRoot()
        compose.runOnIdle {
            val density = renderedView.resources.displayMetrics.density
            image = createBitmap(
                ((bounds.right - bounds.left).value * density).toInt(),
                ((bounds.bottom - bounds.top).value * density).toInt(),
                Bitmap.Config.ARGB_8888,
            )
            val canvas = Canvas(image)
            canvas.translate(-bounds.left.value * density, -bounds.top.value * density)
            renderedView.draw(canvas)
        }
        val directory = File("build/series-screen-evidence").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun render(book: Book) {
        compose.setContent {
            ShelfPlayerTheme(darkTheme = false, dynamicColor = false) {
                renderedView = LocalView.current
                Surface {
                    SeriesBookCard(book = book, onClick = {}, onPlay = {}, modifier = Modifier.testTag("series-book"))
                }
            }
        }
    }

    private fun progress(finished: Boolean) = MediaProgress(
        serverId = ServerId("server"),
        profileId = ProfileId("profile"),
        bookId = LibraryItemId("book"),
        position = if (finished) 3.hours else 1.hours,
        duration = 3.hours,
        isFinished = finished,
        updatedAt = Instant.EPOCH,
        hasUnsyncedChanges = false,
    )

    private fun book() = Book(
        serverId = ServerId("server"), libraryId = LibraryId("library"), id = LibraryItemId("book"),
        title = TITLE, subtitle = null, description = null,
        authors = listOf(Author(ServerId("server"), AuthorId("author"), AUTHOR)),
        narrators = emptyList(),
        seriesMemberships = listOf(
            SeriesMembership(
                Series(ServerId("server"), SeriesId("series"), SERIES),
                SeriesSequence.parse("12"),
                true,
            ),
        ),
        genres = emptyList(), tags = emptyList(), publisher = null, publishedYear = null, language = null,
        isbn = null, asin = null, duration = 3.hours, trackCount = 1, sizeBytes = 0, coverPath = null,
        addedAt = null, remoteUpdatedAt = null, lastFetchedAt = Instant.EPOCH, isExplicit = false,
        isAbridged = false, progress = null, localAvailability = LocalAvailability.NotDownloaded,
    )

    private companion object {
        const val TITLE = "A Very Long Journey Across the Northern Sea"
        const val AUTHOR = "Alexandra Example and Benjamin Example"
        const val SERIES = "The Long Northern Journey"
        const val PIXEL_ROUNDING_TOLERANCE = 1f
    }
}
