package com.example.shelfplayer.feature.author

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.example.shelfplayer.core.designsystem.theme.ShelfPlayerTheme
import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.MediaProgress
import com.example.shelfplayer.core.model.library.Series
import com.example.shelfplayer.core.model.library.SeriesMembership
import com.example.shelfplayer.domain.library.AuthorShelf
import com.example.shelfplayer.domain.library.authorShelfFor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w414dp-h1280dp")
class AuthorScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var renderedView: View
    private val authorId = AuthorId("author")
    private val author = Author(com.example.shelfplayer.core.model.ServerId("srv_books"), authorId, "Ada Ledger")
    private val title = "The long standalone title that must remain readable at large text sizes"

    @Test
    fun `mixed groups open existing details and expose no Play action`() {
        var selectedBook: LibraryItemId? = null
        var selectedSeries: SeriesId? = null
        var back = false
        val shelf = shelf(complete = true)
        compose.setContent {
            AuthorScreen(AuthorUiState(shelf, false), { selectedBook = it }, { selectedSeries = it }, { back = true })
        }
        compose.onNodeWithText("Series").assertIsDisplayed()
        compose.onNodeWithText("Voyage").performClick()
        assertEquals(SeriesId("series"), selectedSeries)
        compose.onNodeWithText("Standalone books").assertIsDisplayed()
        compose.onNodeWithText(title).performClick()
        assertEquals(LibraryItemId("standalone"), selectedBook)
        compose.onAllNodesWithContentDescription("Play", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(back)
    }

    @Test
    fun `opaque series identity cannot collide with the section heading key`() {
        val base = shelf(true)
        val shelf = base.copy(series = base.series.map { it.copy(series = it.series.copy(id = SeriesId("heading"))) })
        compose.setContent { AuthorScreen(AuthorUiState(shelf, false), {}, {}, {}) }
        compose.onNodeWithText("Voyage").assertIsDisplayed()
        compose.onNodeWithText("Standalone books").assertIsDisplayed()
    }

    @Test
    fun `unverified catalogue never announces a series as completed`() {
        compose.setContent { AuthorScreen(AuthorUiState(shelf(false), false), {}, {}, {}) }
        compose.onNodeWithText("Completion not verified").assertIsDisplayed()
        compose.onNodeWithText("1 book · finished").assertDoesNotExist()
        compose.onNodeWithText("Finished").assertIsDisplayed()
    }

    @Test
    fun `complete catalogue announces authoritative series and standalone completion`() {
        compose.setContent { AuthorScreen(AuthorUiState(shelf(true), false), {}, {}, {}) }
        compose.onNodeWithText("1 book · finished").assertIsDisplayed()
        compose.onNodeWithText("Finished").assertIsDisplayed()
        compose.onNodeWithText("Completion not verified").assertDoesNotExist()
    }

    @Test
    fun `loading state announces its label without private metadata`() {
        compose.setContent { AuthorScreen(AuthorUiState(), {}, {}, {}) }
        compose.onNodeWithContentDescription("Loading author").assertIsDisplayed()
        compose.onNodeWithText("Ada Ledger").assertDoesNotExist()
    }

    @Test
    fun `missing author clears private metadata and retains Back`() {
        var back = false
        compose.setContent { AuthorScreen(AuthorUiState(isLoading = false), {}, {}, { back = true }) }
        compose.onNodeWithText("Author not available").assertIsDisplayed()
        compose.onNodeWithText("Ada Ledger").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(back)
    }

    @Test
    @Config(qualifiers = "nb-rNO-w375dp-h1280dp", fontScale = 1.3f)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `native Norwegian author page renders localized groups and unverified completion`() {
        compose.setContent {
            ShelfPlayerTheme(darkTheme = false, dynamicColor = false) {
                renderedView = LocalView.current
                AuthorScreen(AuthorUiState(shelf(false), false), {}, {}, {})
            }
        }
        compose.onNodeWithText("Serier").assertIsDisplayed()
        compose.onNodeWithText("Frittstående bøker").assertIsDisplayed()
        compose.onNodeWithText("Fullføring er ikke bekreftet").assertIsDisplayed()
        capture("author-375dp-font1_3-nb-light")
    }

    @Test
    @Config(qualifiers = "w320dp-h1280dp", fontScale = 2.0f)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `native narrow large text author page preserves full standalone metadata`() {
        compose.setContent {
            ShelfPlayerTheme(darkTheme = true, dynamicColor = false) {
                renderedView = LocalView.current
                Box(Modifier.width(320.dp)) {
                    AuthorScreen(AuthorUiState(shelf(true), false), {}, {}, {})
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText("Finished").assertIsDisplayed()
        capture("author-320dp-font2-dark")
    }

    private fun shelf(complete: Boolean): AuthorShelf {
        val base = com.example.shelfplayer.feature.home.book().copy(authors = listOf(author))
        val finished = base.copy(
            progress = MediaProgress(
                base.serverId,
                ProfileId("profile"),
                base.id,
                3.hours,
                3.hours,
                true,
                Instant.EPOCH,
                false,
            ),
        )
        val standalone = finished.copy(id = LibraryItemId("standalone"), title = title)
        val series = finished.copy(
            id = LibraryItemId("member"),
            title = "First voyage",
            seriesMemberships = listOf(
                SeriesMembership(Series(base.serverId, SeriesId("series"), "Voyage"), SeriesSequence.parse("1"), true),
            ),
        )
        return requireNotNull(authorShelfFor(listOf(series, standalone), authorId, complete))
    }

    private fun capture(name: String) {
        lateinit var image: Bitmap
        compose.runOnIdle {
            image = createBitmap(renderedView.width, renderedView.height, Bitmap.Config.ARGB_8888)
            renderedView.draw(Canvas(image))
        }
        val directory = File("build/author-screen-evidence").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
